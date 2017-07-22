package util.feed

import play.api.http.MimeTypes
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

trait PlayFeedController extends InjectedController {

  def AtomFeedAction(block: => Future[Result])(implicit exec: ExecutionContext): Action[AnyContent] =
    AtomFeedAction(_ => block)

  def AtomFeedAction(block: Request[AnyContent] => Future[Result])(implicit exec: ExecutionContext): Action[AnyContent] =
    Action.async { implicit request =>
      val TypeApplicationAtom = "application/atom+xml"
      val TypeTextXml = "text/xml"

      val acceptsApplicationAtom = Accepting(TypeApplicationAtom)
      val acceptsApplicationXml = Accepts.Xml
      val acceptsTextXml = Accepting(TypeTextXml)

      render.async {
        case acceptsApplicationAtom() => block(request).map(_.as(TypeApplicationAtom))
        case acceptsApplicationXml() => block(request).map(_.as(MimeTypes.XML))
        case acceptsTextXml() => block(request).map(_.as(TypeTextXml))
      }
    }

}
