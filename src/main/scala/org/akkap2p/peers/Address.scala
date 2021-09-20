package org.akkap2p
package peers

import java.net.URLEncoder

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