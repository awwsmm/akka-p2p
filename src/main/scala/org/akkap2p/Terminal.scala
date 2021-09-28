package org.akkap2p

import akka.actor.typed.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import io.github.awwsmm.zepto.Command
import io.github.awwsmm.zepto.Command.{Quit, help}
import org.akkap2p.actors.User
import org.akkap2p.model.{Address, AddressedMessage}

object Terminal extends StrictLogging {

  private[this] class Commands(onReceive: AddressedMessage => Unit)(implicit system: ActorSystem[User.Command]) {

    final val connect =
      Command("connect", "connects to a peer", { maybeAddress =>
        Address.ifValid(maybeAddress.getOrElse(""))(Actions.connect(_, onReceive))
      })

    final val disconnect =
      Command("disconnect", "disconnects from a peer", { maybeAddress =>
        Address.ifValid(maybeAddress.getOrElse(""))(Actions.disconnect)
      })

    final val logout =
      Command("logout", "disconnects from all peers", {
        _ => Actions.disconnectAll()
      })

    final val send =
      Command("send", "send a message to a peer", {
        case Some(s"$maybeAddress $message") => Address.ifValid(maybeAddress) { Actions.send(_, message) }
        case _ =>
          val msg = s"ERROR: usage: send <host:port> <remaining text to send>"
          logger.error(msg)
          Some(msg)
      })

    final val broadcast =
      Command("broadcast", "broadcast a message to all peers", {
        case Some(value) if value.trim.nonEmpty => Actions.broadcast(value)
        case _ =>
          val msg = s"ERROR: usage: broadcast <remaining text to send>"
          logger.error(msg)
          Some(msg)
      })

    val all: Set[Command] = Set(connect, disconnect, logout, send, broadcast, Quit)
  }

  def apply(prompt: String, onReceive: AddressedMessage => Unit)(implicit system: ActorSystem[User.Command]): io.github.awwsmm.zepto.Terminal = {
    val commands = new Commands(onReceive)
    new io.github.awwsmm.zepto.Terminal(commands.all + help(commands.all), prompt)
  }

}
