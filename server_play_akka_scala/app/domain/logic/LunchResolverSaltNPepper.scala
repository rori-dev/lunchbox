package domain.logic

import java.net.URL

import domain.models.{LunchOffer, LunchProvider}
import domain.util.Html
import org.apache.commons.lang3.StringEscapeUtils
import org.joda.money.{CurrencyUnit, Money}
import java.time.{DayOfWeek, LocalDate}
import java.time.format.DateTimeFormatter

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.matching.Regex
import scala.xml._

class LunchResolverSaltNPepper(dateValidator: DateValidator) extends LunchResolver {

  implicit class RegexContext(sc: StringContext) {
    def r = new Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }

  sealed abstract class OfferSection(val title: String, val mondayOffset: Int)

  object OfferSection {
    case object MONTAG extends OfferSection("Montag", 0)
    case object DIENSTAG extends OfferSection("Dienstag", 1)
    case object MITTWOCH extends OfferSection("Mittwoch", 2)
    case object DONNERSTAG extends OfferSection("Donnerstag", 3)
    case object FREITAG extends OfferSection("Freitag", 4)
    case object WOCHENANGEBOT extends OfferSection("Unser Wochenangebot", -1)

    val weekdaysValues = List[OfferSection](MONTAG, DIENSTAG, MITTWOCH, DONNERSTAG, FREITAG)
    // TODO: improve with macro, see https://github.com/d6y/enumeration-examples & http://underscore.io/blog/posts/2014/09/03/enumerations.html
    val values = weekdaysValues :+ WOCHENANGEBOT
  }

  override def resolve: Future[Seq[LunchOffer]] =
    Future { resolve(new URL("http://www.partyservice-rohde.de/bistro-angebot-der-woche/")) }

  private[logic] def resolve(url: URL): Seq[LunchOffer] = {
    val siteAsXml = Html.load(url)

    val divs = (siteAsXml \\ "div").filter(n => (n \@ "class") == "wpb_text_column wpb_content_element")
    val mondayOpt = resolveMonday(divs)

    mondayOpt.map(resolveOffers(divs, _)).getOrElse(Nil)
  }

  private def resolveMonday(nodes: NodeSeq): Option[LocalDate] =
    nodes
      .map(parseName(_).replaceAll("\n", ""))
      .find(_.contains(" Uhr"))
      .flatMap(parseDay)
      .map(toMonday)

  private def resolveOffers(nodes: NodeSeq, monday: LocalDate): Seq[LunchOffer] = {
    var section2node = Map[OfferSection, Node]()

    for (node <- nodes) {
      val h4Opt = (node \\ "h4").headOption
      val title = h4Opt.map(parseName).getOrElse("")

      for (section <- OfferSection.values)
        if (section.title == title)
          section2node += section -> node
    }

    val (dayNodes, weekNodes) = section2node.partition(section => OfferSection.weekdaysValues.contains(section._1))
    val weekdayOffers = resolveWeekdayOffers(dayNodes, monday)
    weekdayOffers ++ resolveWeekOffers(weekNodes, weekdayOffers.map(_.day).toSet)
  }

  private def resolveWeekdayOffers(section2node: Map[OfferSection, Node], monday: LocalDate): Seq[LunchOffer] = {
    var result = Seq[LunchOffer]()
    for ((section, node) <- section2node) {
      val pureOffers = resolveSectionOffers(section, node)
      result ++= pureOffers.map(_.copy(day = monday.plusDays(section.mondayOffset)))
    }
    result
  }

  private def resolveWeekOffers(section2node: Map[OfferSection, Node], days: Set[LocalDate]): Seq[LunchOffer] = {
    var result = Seq[LunchOffer]()
    for (
      (section, node) <- section2node;
      pureOffers = resolveSectionOffers(section, node);
      weekday <- days
    ) {
      result ++= pureOffers.map(offer => offer.copy(name = s"Wochenangebot: ${offer.name}", day = weekday))
    }
    result
  }

  private def resolveSectionOffers(section: OfferSection, node: Node): Seq[LunchOffer] = {
    val tds = (node \\ "td")
    tds.grouped(2).flatMap {
      case Seq(nameNode, priceNode) =>
        parsePrice(priceNode).map { price =>
          val name = parseName(nameNode)
            .replaceAll("^Topp-Preis:", "")
            .replaceAll("^Tipp:", "")
            .replaceAll("[0-9]{1,2}(, [0-9]{1,2})*$", "")
            .trim
          LunchOffer(0, name, LocalDate.now(), price, LunchProvider.SALT_N_PEPPER.id)
        }
      case _ => None
    }.toSeq
  }

  /**
   * Erzeugt ein LocalDate aus dem Format "*dd.mm.yyyy*"
   *
   * @param text Text
   * @return
   */
  private def parseDay(text: String): Option[LocalDate] = text match {
    case r""".*(\d{2}\.\d{2}\.\d{4})$dayString.*""" => parseLocalDate(dayString, "dd.MM.yyyy")
    case _ => None
  }

  private def toMonday(day: LocalDate): LocalDate = day.`with`(DayOfWeek.MONDAY)

  /**
   * Erzeugt ein Money-Objekt (in EURO) aus dem Format "*0,00*"
   *
   * @param node HTML-Node mit auszuwertendem Text
   * @return
   */
  private def parsePrice(node: Node): Option[Money] = node.text match {
    case r""".*(\d{1,})$major[\.,](\d{2})$minor.*""" => Some(Money.ofMinor(CurrencyUnit.EUR, major.toInt * 100 + minor.toInt))
    case _ => None
  }

  private def parseName(node: Node): String = {
    val pureText = StringEscapeUtils.unescapeHtml4(node.text.trim)
    pureText.replaceAll("\n", " ").replaceAll("  ", " ")
  }

  private def parseLocalDate(dateString: String, dateFormat: String): Option[LocalDate] =
    try {
      Some(LocalDate.from(DateTimeFormatter.ofPattern(dateFormat).parse(dateString)))
    } catch {
      case exc: Throwable => None
    }
}
