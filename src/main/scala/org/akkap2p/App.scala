package org.akkap2p

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.github.awwsmm.zepto.Command.{Quit, help}
import io.github.awwsmm.zepto.{Command, Terminal}
import org.akkap2p.peers.{Address, User}
import pureconfig.ConfigSource
import spray.json.RootJsonFormat
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
            logger.info(s"Received external request to connect to $address")
            system.ref ! User.RequestConnection(address, x => logger.info(x), 10.seconds)
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
            logger.info(s"Received external request to disconnect from $address")
            system.ref ! User.Disconnect(address)
            complete(StatusCodes.OK)
          }
        } ~
          put {
            logger.info("Received external request to disconnect from all connected org.akkap2p.peers")
            system.ref ! User.DisconnectAll
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

      val send: Route = path("send") {

        final case class AddressedMessage(address: Address, message: String)
        implicit val format: RootJsonFormat[AddressedMessage] = jsonFormat2(AddressedMessage)

        post {
          entity(as[AddressedMessage]) { case AddressedMessage(address, body) =>
            logger.info(s"Received external request to send message to $address")
            system.ref ! User.Send(address, body)
            complete(StatusCodes.OK)
          } ~
            entity(as[String]) { body =>
              logger.info("Received external request to send message to all connected org.akkap2p.peers")
              system.ref ! User.Broadcast(body)
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

    // connect to all preconfigured org.akkap2p.peers
    config.peers.split(",").filter(_.nonEmpty).map(_.split(":")).foreach {
      case Array(host, port) =>
        Try(port.toInt) match {
          case Failure(_) =>
            logger.error(s"""Unable to register preconfigured peer "$host:$port" -- port "$port" must be an Int""")

          case Success(value) =>
            val address = Address(host, value)
            logger.info(s"Attempting to connect to preconfigured peer at $address")
            system.ref ! User.RequestConnection(address, x => logger.info(x), 10.seconds)
            complete(StatusCodes.OK)
        }
      case array =>
        logger.error(s"""Unable to parse preconfigured peer "${array.mkString(":")}" as an Address (must follow host:port format)""")
    }

    // TODO clean up command-line arguments

    object Commands {

      val hello: Command = Command("hello", "says hello", _ => println("Hello, World!"))

      val connect: Command = Command("connect", "connects to a peer", peer => {
        peer.split(":") match {
          case Array(host, port) =>
            Try(port.toInt) match {
              case Failure(_) =>
                logger.error(s"""Unable to register peer "$host:$port" -- port "$port" must be an Int""")

              case Success(value) =>
                val address = Address(host, value)
                logger.info(s"Attempting to connect to peer at $address")
                system.ref ! User.RequestConnection(address, x => logger.info(x), 10.seconds)
                complete(StatusCodes.OK)
            }
          case array =>
            logger.error(s"""Unable to parse peer "${array.mkString(":")}" as an Address (must follow host:port format)""")
        }
      })

      val send: Command = Command("send", "send a message to a peer", args => {
        args.split(" ", 2) match {
          case Array(peer, message) =>
            peer.split(":") match {
              case Array(host, port) =>
                Try(port.toInt) match {
                  case Failure(_) =>
                    logger.error(s"""Unable to send message to peer "$host:$port" -- port "$port" must be an Int""")

                  case Success(value) =>
                    val address = Address(host, value)
                    system.ref ! User.Send(address, message)
                }
            }

          case _ =>
            logger.error(s"usage: send host:port remaining text to send")
        }
      })

      val broadcast: Command = Command("broadcast", "broadcast a message to all peers", message => {
        system.ref ! User.Broadcast(message)
      })

    }

    val commands = Set(Quit, Commands.hello, Commands.connect, Commands.send, Commands.broadcast)

    val terminal = Terminal(commands + help(commands), "\nakka-p2p> ")

    terminal.run()

    // TODO fix this so it shuts down cleanly
    bindingFuture.flatMap { binding =>
      system.ref ! User.DisconnectAll
      binding.unbind()

    }.onComplete { _ =>
      system.terminate()
    }

  }
}
