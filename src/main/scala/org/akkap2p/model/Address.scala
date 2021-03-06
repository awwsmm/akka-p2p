package org.akkap2p
package model

import scala.util.matching.Regex

import java.net.URLEncoder

import com.typesafe.scalalogging.StrictLogging

/**
 * The address of a peer, with a `host` and a `port`.
 *
 * @param host the hostname or IP address of the peer
 * @param port the port on which '''akka-p2p''' is running on the peer
 */
final case class Address(host: String, port: Int) {
  override def toString: String = s"$host:$port"
  def urlEncoded: String = URLEncoder.encode(toString, "UTF-8")
}

object Address extends StrictLogging {

  /**
   * Attempts to parse the given `String` as an [[Address]] in '''host:port''' format and apply the function `f` to it.
   * @param maybeAddress the `String` to parse as an `Address`
   * @param f the function to apply to the `Address`
   */
  def ifValid(maybeAddress: String)(f: Address => Option[String]): Option[String] = fromString(maybeAddress).flatMap(f)

  /**
   * Attempts to parse the given `String` as an [[Address]] in '''host:port''' format
   * @param maybeAddress the `String` to parse as an `Address`
   * @return a `Some[Address]` if `maybeAddress` is in '''host:port''' format, where `port` is an integer, [[None]] otherwise
   */
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