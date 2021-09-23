package org.akkap2p

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.peers.{Address, AddressedMessage, User}
import pureconfig.ConfigSource
import upickle.default.write

object App extends Directives with StrictLogging with JSONSupport {

  // TODO send logs to file instead of to stdout

  // TODO assess required libraries in build.sbt

  def main(args: Array[String]): Unit = {

    final case class AppConfig(httpHost: String, httpPort: Int, peers: String)

    val config: AppConfig = {
      import pureconfig.generic.auto._

      ConfigSource.default.load[AppConfig] match {
        case Right(value) => value
        case Left(reasons) =>
          val msg = s"Cannot start App. Invalid config: $reasons"
          logger.error(msg)
          throw new IllegalArgumentException(msg)
      }
    }

    val ConnectionEndpoint = "connect"

    implicit val system: ActorSystem[User.Command] = ActorSystem(User.behavior(config.httpPort, ConnectionEndpoint), "app")
    implicit val executor: ExecutionContextExecutor = system.executionContext
    implicit val scheduler: Scheduler = system.scheduler

    // TODO clean up all these .seconds and askTimeout timeouts -- into config

    object Routes {

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
       * of all connected and disconnected org.akkap2p.peers.
       */
      val peers: Route = path("peers") {
        get {
          logger.info(s"Received external query for org.akkap2p.peers")

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
      val send: Route = path("send") {
        post {
          entity(as[AddressedMessage]) { case AddressedMessage(address, body) =>
            Actions.send(address, body)
            complete(StatusCodes.OK)
          } ~
          entity(as[String]) { body =>
            Actions.broadcast(body)
            complete(StatusCodes.OK)
          }
        }
      }

    }

    val bindingFuture = Http()
      .newServerAt(config.httpHost, config.httpPort)
      .adaptSettings(_.mapWebsocketSettings(_.withPeriodicKeepAliveMaxIdle(30.seconds)))
      .bind(Routes.connect ~ Routes.disconnect ~ Routes.peers ~ Routes.send)

    logger.info(s"Listening on ${config.httpHost}:${config.httpPort}")

    // connect to all preconfigured peers
    config.peers.split(",").filter(_.nonEmpty).flatMap(Address.fromString).foreach(Actions.connect)

    // run the REPL
    REPL().run()

    // TODO fix this so it shuts down cleanly
    bindingFuture.flatMap { binding =>
      system.ref ! User.DisconnectAll
      binding.unbind()

    }.onComplete { _ =>
      system.terminate()
    }

  }
}
