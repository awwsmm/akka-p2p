package org.akkap2p

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.actors.User
import org.akkap2p.model.Address

/**
 * Defines the API of akka-p2p.
 */
class API()(implicit system: ActorSystem[User.Command]) extends Directives with StrictLogging with JSONSupport {
  import API._

  implicit val scheduler: Scheduler = system.scheduler


  /**
   * Route for connecting to a peer.
   * {{{
   * /connect
   * }}}
   */
  val connect: Route = path(ConnectionEndpoint) {
    put {
      entity(as[Address]) { address =>
        Actions.connect(address)
        complete(StatusCodes.OK)
      }
    } ~
    extractHost { host =>
      parameters("port") { portStr =>

        // only used internally, so we trust `portStr` to be an Int
        val address = Address(host, portStr.toInt)
        logger.info(s"Received p2p connection request from $address")

        extractWebSocketUpgrade { upgrade =>
          logger.debug(s"Attempting to accept incoming connection from $address")

          implicit val askTimeout: Timeout = 3.seconds

          val futureResponse: Future[HttpResponse] =
            system.ref ? {
              User.AcceptConnection(_, upgrade, address, x => logger.info(x))
            }

          onComplete(futureResponse) {
            case Failure(exception) => complete(exception)
            case Success(response) => complete(response)
          }
        }
      }
    }
  }

}

object API {

  val ConnectionEndpoint = "connect"

}
