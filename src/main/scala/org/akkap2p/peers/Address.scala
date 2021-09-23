package org.akkap2p
package peers

import scala.util.matching.Regex

import java.net.URLEncoder

import com.typesafe.scalalogging.StrictLogging

/**
 * The address of a [[Peer]], with a `host` and a `port`.
 *
 * @param host the hostname or IP address of the peer
 * @param port the port on which '''akka-p2p''' is running on the peer
 */
final case class Address(host: String, port: Int) {
  override def toString: String = s"$host:$port"
  def urlEncoded: String = URLEncoder.encode(toString, "UTF-8")
}

object Address extends StrictLogging {
  def ifValid(maybeAddress: String)(f: Address => Unit): Unit = fromString(maybeAddress).foreach(f)

  def fromString(maybeAddress: String): Option[Address] = {
    val Port: Regex = """(\d+)""".r
    maybeAddress match {
      case s"$host:${Port(port)}" =>
        Some(Address(host, Integer.parseInt(port)))
      case _ =>
        logger.error(s"Unable to parse $maybeAddress as a peer address. Must be in host:port format, where port is an integer.")
        None
    }
  }
}