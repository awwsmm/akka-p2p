name := "akka-p2p"

version := "0.1"

scalaVersion := "2.13.5"

val AkkaVersion = "2.6.16"
val AkkaHttpVersion = "10.2.6"

libraryDependencies ++= Seq(

  // Akka
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion,

  // Config
  "com.github.pureconfig" %% "pureconfig" % "0.16.0",

  // JSON marshalling / unmarshalling
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,

  // Logging
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.14.1",

  // REPL
  "io.github.awwsmm" %% "zepto" % "1.0.0"

)