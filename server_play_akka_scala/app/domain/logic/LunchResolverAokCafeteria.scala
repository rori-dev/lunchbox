package domain.logic

import java.io.FileNotFoundException
import java.net.URL
import java.time.format.DateTimeFormatter

import domain.models.{LunchOffer, LunchProvider}
import domain.util.{PDFTextGroupStripper, TextGroup, TextLine}
import domain.util.Html
import org.apache.pdfbox.pdmodel.PDDocument
import org.joda.money.{CurrencyUnit, Money}
import java.time.{DayOfWeek, LocalDate}

import util.PlayLogging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.matching.Regex

class LunchResolverAokCafeteria(dateValidator: DateValidator) extends LunchResolver with PlayLogging {

  implicit class RegexContext(sc: StringContext) {
    def r = new Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }

  sealed abstract class PdfSection(val name: String, val order: Int)

  object PdfSection {
    case object TABLE_HEADER extends PdfSection("", -1)
    case object MONTAG extends PdfSection("Montag", 0)
    case object DIENSTAG extends PdfSection("Dienstag", 1)
    case object MITTWOCH extends PdfSection("Mittwoch", 2)
    case object DONNERSTAG extends PdfSection("Donnerstag", 3)
    case object FREITAG extends PdfSection("Freitag", 4)

    val weekdayValues = List[PdfSection](MONTAG, DIENSTAG, MITTWOCH, DONNERSTAG, FREITAG)

    // TODO: improve with macro, see https://github.com/d6y/enumeration-examples & http://underscore.io/blog/posts/2014/09/03/enumerations.html
    val values = TABLE_HEADER :: weekdayValues
  }

  case class OfferRow(name: String, priceOpt: Option[Money]) {
    def merge(otherRow: OfferRow): OfferRow = {
      val newName = Seq(name, otherRow.name).filter(!_.isEmpty).mkString(" ")
      OfferRow(newName, priceOpt.orElse(otherRow.priceOpt))
    }

    def isValid = !name.isEmpty && priceOpt.isDefined
  }

  override def resolve: Future[Seq[LunchOffer]] =
    Future { resolvePdfLinks(new URL("https://www.hotel-am-ring.de/aok-cafeteria.html")) }
      .flatMap(relativePdfPaths => resolveFromPdfs(relativePdfPaths))

  private[logic] def resolvePdfLinks(htmlUrl: URL): Seq[String] = {
    val siteAsXml = Html.load(htmlUrl)

    val links = (siteAsXml \\ "a").map(_ \@ "href")
    links.filter(_ matches """.*/[a-zA-Z]{3}[0-9_\.\-]*.pdf""").toSet.toList
  }

  private def resolveFromPdfs(relativePdfPaths: Seq[String]): Future[Seq[LunchOffer]] = {
    val listOfFutures = relativePdfPaths.map(relativePdfPath =>
      Future { resolveFromPdf(new URL("https://www.hotel-am-ring.de/" + relativePdfPath)) })
    Future.sequence(listOfFutures).map(listOfLists => listOfLists.flatten)
  }

  private[logic] def resolveFromPdf(pdfUrl: URL): Seq[LunchOffer] = {
    val lines = extractPdfContent(pdfUrl)

    parseMonday(lines)
      .filter(dateValidator.isValid)
      .map(resolveFromPdfContent(pdfUrl, lines, _))
      .getOrElse(Nil)
  }

  private[logic] def resolveFromPdfContent(pdfUrl: URL, lines: Seq[TextLine], monday: LocalDate): Seq[LunchOffer] = {
    val section2lines = groupBySection(lines)

    var offers = Seq[LunchOffer]()

    section2lines.get(PdfSection.TABLE_HEADER) match {
      case Some(Seq(priceHeader)) =>
        val normalizedPriceTexts = normalizePriceTexts(priceHeader.texts)
        val xCoords = normalizedPriceTexts.map(_.xMid)
        val prices = normalizedPriceTexts.flatMap(e => parsePrice(e.toString))
        for (
          weekday <- PdfSection.weekdayValues;
          lines <- section2lines.get(weekday);
          (x, price) <- xCoords.zip(prices)
        ) parseDayOffer(lines, x, weekday, monday, price).foreach(offers :+= _)

      case None => logger.warn(s"Preis-Header nicht gefunden in $pdfUrl")
    }

    offers
  }

  private[logic] def parseMonday(lines: Seq[TextLine]): Option[LocalDate] = parseMondayByTexts(lines.map(_.toString))

  private[logic] def parseMondayByTexts(lines: Seq[String]): Option[LocalDate] = {
    // alle Datumse aus PDF ermitteln
    val days = lines.flatMap(line => parseDay(line.toString))
    val mondays = days.map(_.`with`(DayOfWeek.MONDAY))
    // den Montag der am häufigsten verwendeten Woche zurückgeben
    mondays match {
      case Nil => None
      case _ => Option(mondays.groupBy(identity).maxBy(_._2.size)._1)
    }
  }

  private def groupBySection(lines: Seq[TextLine]): Map[PdfSection, Seq[TextLine]] = {
    var result = Map[PdfSection, Seq[TextLine]]()

    for (priceHeader <- lines.find(_.allTextsMatch("""^\d{1,}[.,]\d{2} *€$""")))
      result += PdfSection.TABLE_HEADER -> Seq(priceHeader)

    for (Seq(header) <- result.get(PdfSection.TABLE_HEADER)) {
      val linesBelowHeader = lines.filter(_.y > header.y)

      var linesForDay = Seq[TextLine]()
      for (line <- linesBelowHeader) {
        if (line.oneTextMatches(""".*Zusatzstoffe.*""")) {
          // an Feiertagen gibt es keine Angebote ... und keine Zusatzstoffe
          if (line.texts.size > 1) {
            val weekdayOpt = findWeekdaySection(linesForDay)
            weekdayOpt.foreach(day => result += day -> linesForDay)
          }
          linesForDay = Nil
        } else if (line.oneTextMatches(""".*(Nährwerte|Kohlenhydrate).*""")) {
          // ignore line
        } else {
          linesForDay :+= line
        }
      }
    }

    result
  }

  def parseDayOffer(lines: Seq[TextLine], xRef: Float, weekday: PdfSection, monday: LocalDate, price: Money): Option[LunchOffer] = {
    val day = monday.plusDays(weekday.order)
    val name = lines.flatMap(_.texts.filter(_.xIn(xRef))).mkString(" ")
    Some(LunchOffer(0, parseName(name), day, price, LunchProvider.AOK_CAFETERIA.id))
  }

  private def findWeekdaySection(lines: Seq[TextLine]): Option[PdfSection] = {
    val weekdaysRegex = PdfSection.weekdayValues.map(_.name).mkString("^(", "|", ")$")
    val weekdayTextGroupOpt = lines.flatMap(_.texts.find(_.toString.matches(weekdaysRegex))).headOption
    weekdayTextGroupOpt.flatMap(e => parseWeekdaySection(e.toString))
  }

  /**
   * Manchmal wird die fette Schrift und Übereinanderlegen von Texten erreicht.
   * Zur Verarbeitung der Mittagsangebote brauchen wir jedoch lediglich "ein Layer".
   */
  private[logic] def normalizePriceTexts(texts: Seq[TextGroup]): Seq[TextGroup] =
    texts.groupBy(_.toString)
      .values.map(_.minBy(_.xMin))
      .toSeq.sortBy(_.xMin)

  private def parseDay(dayString: String): Option[LocalDate] = dayString match {
    case r""".*(\d{2}.\d{2}.\d{4})$dayString.*""" => parseLocalDate(dayString, "dd.MM.yyyy")
    case r""".*(\d{2}.\d{2}.\d{2})$dayString.*""" => parseLocalDate(dayString, "dd.MM.yy")
    case r""".*(\d{2}.\d{2}.)$dayString.*""" =>
      val yearToday = LocalDate.now.getYear
      val year = if (LocalDate.now.getMonthValue == 12 && dayString.endsWith("01.")) yearToday + 1 else yearToday
      parseLocalDate(dayString + year.toString, "dd.MM.yyyy")
    case _ => None
  }

  /**
   * Erzeugt ein Money-Objekt (in EURO) aus dem Format "*0,00*"
   *
   * @param priceString String im Format "*0,00*"
   * @return
   */
  private def parsePrice(priceString: String): Option[Money] = priceString match {
    case r""".*(\d{1,})$major[.,](\d{2})$minor.*""" => Some(Money.ofMinor(CurrencyUnit.EUR, major.toInt * 100 + minor.toInt))
    case _ => None
  }

  private def parseName(text: String): String = text.trim.replaceAll("  ", " ")

  private def parseLocalDate(dateString: String, dateFormat: String): Option[LocalDate] =
    try {
      Some(LocalDate.from(DateTimeFormatter.ofPattern(dateFormat).parse(dateString)))
    } catch {
      case exc: Throwable => None
    }

  private def parseWeekdaySection(weekdayString: String): Option[PdfSection] =
    PdfSection.weekdayValues.find(_.name == weekdayString)

  private def extractPdfContent(pdfUrl: URL): Seq[TextLine] = {
    var optPdfDoc: Option[PDDocument] = None
    var pdfContent = Seq[TextLine]()

    try {
      optPdfDoc = Some(PDDocument.load(pdfUrl))
      for (pdfDoc <- optPdfDoc) {
        val stripper = new PDFTextGroupStripper
        pdfContent = stripper.getTextLines(pdfDoc)
      }
    } catch {
      case fnf: FileNotFoundException => logger.error(s"file $pdfUrl not found")
      case t: Throwable => logger.error(s"Fehler beim Einlesen von $pdfUrl", t)
    } finally {
      optPdfDoc.foreach(_.close())
    }
    pdfContent
  }

}
