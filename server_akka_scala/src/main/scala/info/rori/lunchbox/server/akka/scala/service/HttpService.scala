package info.rori.lunchbox.server.akka.scala.service

import akka.actor.{Actor, ActorLogging, Props}
import akka.http.Http
import akka.http.marshalling.ToResponseMarshallable
import akka.http.model.HttpResponse
import akka.http.server.{Directives, Route}
import akka.stream.scaladsl.ImplicitFlowMaterializer
import akka.util.Timeout
import info.rori.lunchbox.server.akka.scala.ApplicationModule
import info.rori.lunchbox.server.akka.scala.service.api.v1.ApiRouteV1
import info.rori.lunchbox.server.akka.scala.service.feed.FeedRoute
import org.joda.time.format.DateTimeFormat
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}

object HttpService {
  val Name = "http"

  def props(interface: String, port: Int) = Props(new HttpService(interface, port)(5.seconds))
}

class HttpService(host: String, port: Int)(implicit askTimeout: Timeout)
  extends Actor
  with ImplicitFlowMaterializer
  with MaintenanceRoute
  with ApiRouteV1
  with FeedRoute {

  log.info(s"Starting server at $host:$port")
  log.info(s"To shutdown, send http://$host:$port/shutdown")

  Http(context.system).bind(host, port).startHandlingWith(route)

  def route: Route = apiRouteV1 ~ feedRoute ~ maintenanceRoute

  override def receive = {
    case msg => log.warning("unhandled message in HttpService: " + msg)
  }
}


object HttpRoute {
  val NotFound = akka.http.model.StatusCodes.NotFound
  val InternalServerError = akka.http.model.StatusCodes.InternalServerError
}

trait HttpRoute
  extends Actor
  with ActorLogging
  with Directives {

  implicit val timeout: Timeout = 1.second // Timeout for domain actor calls in route

  implicit def executor: ExecutionContextExecutor = context.dispatcher

  //  implicit val materializer: FlowMaterializer = ActorFlowMaterializer() // necessary for unmarshelling


  implicit class RichFutureToResponseMarshallable(val f: Future[ToResponseMarshallable]) {
    def recoverOnError(message: String) = f.recover[ToResponseMarshallable] {
      case exc: Throwable =>
        log.error(exc, s"HTTP call: $message")
        HttpRoute.InternalServerError
    }
  }

  implicit class OptionStringValidation(val optStr: Option[String]) {
    def isValidLocalDate = optStr match {
      case Some(string) =>
        try {
          DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate(string)
          true
        } catch {
          case _: Throwable => false
        }
      case None => true
    }
  }

}

trait HttpConversions extends DefaultJsonProtocol {

  import akka.http.marshalling.ToResponseMarshallable
  import spray.json._
  import akka.http.marshallers.sprayjson.SprayJsonSupport

  /**
   * Konvertiert zwischen Domain Model & API Model
   * <p>
   *
   * @param domain2api Methode zum Konvertieren von Domain Model zu API Model
   * @tparam D Typ des Domain Models
   * @tparam A Typ des API Models
   */
  class ModelConverter[D, A](domain2api: Function[D, A]) {
    def toApiModel(modelEntity: D) = domain2api(modelEntity)
  }

  /**
   * Wandelt Domain-Messages in HTTP um.
   * <p>
   *
   * @param resultFuture domain result as future
   * @tparam R type of resulting domain message
   * @tparam D type of domain model
   * @tparam A type of api model
   */
  implicit class DomainResult2HttpJsonResponse[R <: Any, D <: Any, A <: Any]
  (resultFuture: Future[R])
  (implicit val domain2api: ModelConverter[D, A], implicit val jsonFormatter: spray.json.RootJsonFormat[A], implicit val executor: ExecutionContextExecutor) {

    implicit val printer: spray.json.JsonPrinter = CompactPrinter

    implicit val marshaller = SprayJsonSupport.sprayJsValueMarshaller[A]

    def mapSeqToJsonResponse(f: R => Seq[D]) = resultFuture.map(msg => toResponse(f(msg)))

    def mapOptionToJsonResponse(f: R => Option[D]) = resultFuture.map(msg => toResponse(f(msg)))

    private def toResponse(elems: Seq[D]): ToResponseMarshallable = elems.map(elem => domain2api.toApiModel(elem)).toJson

    private def toResponse(optElem: Option[D]): ToResponseMarshallable = optElem match {
      case Some(elem) => domain2api.toApiModel(elem).toJson
      case None => HttpRoute.NotFound
    }
  }

}


/**
 * Stellt Wartungsroutinen über die HTTP-Schnittstelle bereit.
 */
trait MaintenanceRoute
  extends HttpRoute {

  def maintenanceRoute =
    logRequest(context.system.name) {
      path("shutdown") {
        get {
          complete {
            // TODO: Shutdown an ApplicationRoot schicken
            context.system.scheduler.scheduleOnce(500.millis, self, ApplicationModule.Shutdown)
            log.info("Shutting down now ...")
            HttpResponse(entity = "Shutting down now ...")
          }
        }
      }
    }
}
