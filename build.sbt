name := "akka-p2p"

version := "0.1"

scalaVersion := "2.13.5"

val AkkaVersion = "2.6.14"
val AkkaHttpVersion = "10.2.4"

libraryDependencies ++= Seq(

  // Scalactic, ScalaTest, and ScalaCheck
  "org.scalactic" %% "scalactic" % "3.2.9",
  "org.scalatest" %% "scalatest" % "3.2.9" % "test",
  "org.scalacheck" %% "scalacheck" % "1.14.1" % "test",
  "org.scalatestplus" %% "scalacheck-1-15" % "3.2.9.0" % "test",

  // uPickle, uJSON
  "com.lihaoyi" %% "upickle" % "1.3.14",
  "com.lihaoyi" %% "upickle-core" % "1.3.14",
  "com.lihaoyi" %% "ujson-json4s" % "1.3.14",

  // JSON marshalling / unmarshalling
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,

  // log4j
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.3",
  "org.slf4j" % "slf4j-api" % "1.7.30",
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.3",
  "org.apache.logging.log4j" % "log4j-api" % "2.13.3",

  // config
  "com.github.pureconfig" %% "pureconfig" % "0.15.0",

  // Akka
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-testkit" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion,

  // interactive shell
  "io.github.awwsmm" %% "zepto" % "1.0.0"

)

scalacOptions := Seq(
//  "-deprecation"
//  , "-feature"
//  , "-unchecked"
  "-Xfatal-warnings"
//  , "-Xlint"
//  , "-Ywarn-numeric-widen"
//  , "-Ywarn-value-discard"
//  , "-Yno-adapted-args"
//  , "-Ywarn-dead-code"
)