package org.akkap2p

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.actors.User
import org.akkap2p.model.{Address, AddressedMessage}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import upickle.default.write

/** Defines the API of '''akka-p2p'''. */
object API extends Directives with StrictLogging with DefaultJsonProtocol with SprayJsonSupport {

  val ConnectionEndpoint = "connect"

  implicit val addressFormat: RootJsonFormat[Address] = jsonFormat2(Address.apply)

  class Routes(onReceive: AddressedMessage => Unit)(implicit system: ActorSystem[User.Command]) {

    implicit val scheduler: Scheduler = system.scheduler

    /** Route for connecting to a peer. */
    val connect: Route = path(ConnectionEndpoint) {
      put {
        entity(as[Address]) { address =>
          Actions.connect(address, onReceive)
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
              system.ref ? { User.AcceptConnection(_, upgrade, address, onReceive) }

            onComplete(futureResponse) {
              case Failure(exception) => complete(exception)
              case Success(response) => complete(response)
            }
          }
        }
      }
    }

    /** Route for disconnecting from a peer. */
    val disconnect: Route = path("disconnect") {
      put {
        entity(as[Address]) { address =>
          Actions.disconnect(address)
          complete(StatusCodes.OK)
        }
      } ~
      put {
        Actions.disconnectAll()
        complete(StatusCodes.OK)
      }
    }

    /**
     * GET request endpoint which returns a JSON response with the addresses
     * of all connected and disconnected peers.
     */
    val peers: Route = path("peers") {
      get {
        logger.info(s"Received external query for peers")

        implicit val askTimeout: Timeout = 3.seconds

        onComplete(system.ref ? User.GetPeers) {
          case Failure(exception) =>
            complete(exception)

          case Success(groups) =>
            val map = groups.map(g => g.name -> g.addresses.map(_.toString))
            complete(write(map.toMap))
        }
      }
    }

    /*
    Example body:
        {
          "address": {
            "host": "localhost",
            "port": 3002
          },
          "message": "hey"
        }
     */
    /** Route to send a message to one or more peers. */
    val send: Route = path("send") {

      implicit val addressedMessageFormat: RootJsonFormat[AddressedMessage] = jsonFormat2(AddressedMessage)

      post {
        entity(as[AddressedMessage]) { case AddressedMessage(address, message) =>
          Actions.send(address, message)
          complete(StatusCodes.OK)
        } ~
        entity(as[String]) { body =>
          Actions.broadcast(body)
          complete(StatusCodes.OK)
        }
      }
    }

    val all: Route = connect ~ disconnect ~ peers ~ send
  }

  def apply(onReceive: AddressedMessage => Unit)(implicit system: ActorSystem[User.Command]) = new Routes(onReceive)

}
