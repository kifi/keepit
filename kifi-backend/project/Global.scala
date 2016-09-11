import com.typesafe.sbt.SbtScalariform._
import com.typesafe.sbteclipse.core.EclipsePlugin.EclipseKeys
import sbt.Keys._
import sbt._

import scalariform.formatter.preferences._
import play.Play.autoImport._
import PlayKeys._
import play.twirl.sbt.Import._


object Global {

  val _scalacOptions = Seq("-unchecked", "-deprecation", "-feature", "-language:reflectiveCalls",
    "-language:implicitConversions", "-language:postfixOps", "-language:dynamics","-language:higherKinds",
    "-language:existentials", "-language:experimental.macros", "-Xmax-classfile-name", "140")

  val _javaTestOptions = Seq("-Xms512m", "-Xmx2g", "-XX:PermSize=256m", "-XX:MaxPermSize=512m")

  val _testOptions = Seq(
    Tests.Argument("sequential", "true"),
    Tests.Argument("threadsNb", "16"),
    Tests.Argument("showtimes", "true"),
    Tests.Argument("stopOnFail", "true"),
    Tests.Argument("failtrace", "true"),
    Tests.Argument("logBuffered", "false"),
    Tests.Argument("traceLevel", "5"),
    Tests.Argument("-oDF")
  )

  val macroParadiseSettings = Seq(
    scalaVersion := "2.11.8",
    scalacOptions ++= _scalacOptions,
    resolvers += Resolver.sonatypeRepo("releases"),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
  )

  val _commonResolvers = Seq(
//??    Resolver.url("sbt-plugin-snapshots", new URL("http://repo.42go.com:4242/fortytwo/content/groups/public/"))(Resolver.ivyStylePatterns),
    // new URL("http://repo.scala-sbt.org/scalasbt/sbt-plugin-snapshots/"))(Resolver.ivyStylePatterns),
    "Typesafe Repository" at "https://repo.typesafe.com/typesafe/releases/",
    // "kevoree Repository" at "http://maven2.kevoree.org/release/",
//??    "FortyTwo Public Repository" at "http://repo.42go.com:4242/fortytwo/content/groups/public/",
//??    "FortyTwo Towel Repository" at "http://repo.42go.com:4242/fortytwo/content/repositories/towel",
    //for org.mongodb#casb
    // "snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    // "releases"  at "https://oss.sonatype.org/content/groups/scala-tools",
    // "terracotta" at "http://www.terracotta.org/download/reflector/releases/",
    // "The Buzz Media Maven Repository" at "http://maven.thebuzzmedia.com"
    "theatr.us" at "http://repo.theatr.us"
  )


  val commonDependencies = Seq(
    jdbc, // todo(andrew): move to sqldb when we discover a way to get Play to support multiple play.plugins files.
    cache,
    ws,
    "com.typesafe.akka" %% "akka-testkit" % "2.3.4" exclude("play", "*"),
    "com.typesafe.play.plugins" %% "play-statsd" % "2.3.0" exclude("play", "*"),
    "com.typesafe.play.plugins" %% "play-plugins-mailer" % "2.3.0" exclude("play", "*"),
    "kifi-securesocial" %% "kifi-securesocial" % "master-20150323" exclude("play", "*"),
    "org.clapper" %% "grizzled-slf4j" % "1.0.2",
    "org.apache.commons" % "commons-compress" % "1.4.1",
    "org.apache.commons" % "commons-math3" % "3.1.1",
    "commons-io" % "commons-io" % "2.4",
    "org.apache.zookeeper" % "zookeeper" % "3.4.5" exclude("org.slf4j", "slf4j-log4j12"),
    "commons-codec" % "commons-codec" % "1.6",
    "com.cybozu.labs" % "langdetect" % "1.1-20120112", // todo(andrew): remove from common. make shared module between search and scraper.
    "org.mindrot" % "jbcrypt" % "0.3m",
    "com.amazonaws" % "aws-java-sdk" % "1.6.12",
    "com.kifi" %% "franz" % "0.3.8",
    "net.sf.uadetector" % "uadetector-resources" % "2013.11",
    "com.google.inject" % "guice" % "4.0",
    "com.google.inject.extensions" % "guice-multibindings" % "4.0",
    "net.codingwell" %% "scala-guice" % "4.0.0",
    "org.imgscalr" % "imgscalr-lib" % "4.2",
    "us.theatr" %% "akka-quartz" % "0.3.0" exclude("c3p0", "c3p0"),
    "org.jsoup" % "jsoup" % "1.8.1",
    "org.bouncycastle" % "bcprov-jdk15on" % "1.50",
    "org.msgpack" %% "msgpack-scala" % "0.6.11",
    "com.kifi" %% "json-annotation" % "0.2",
    "com.mchange" % "c3p0" % "0.9.5-pre8", // todo(andrew): remove from common when C3P0 plugin is in sqldb
    "org.im4java" % "im4java" % "1.4.0" //todo(eishay): means that all services get that. not sure that's this is best
  ) map (_.excludeAll(
    ExclusionRule(organization = "javax.jms"),
    ExclusionRule(organization = "com.sun.jdmk"),
    ExclusionRule(organization = "com.sun.jmx"),
    ExclusionRule(organization = "org.jboss.netty"),
    ExclusionRule("org.scala-stm", "scala-stm_2.10.0")
  ))


  val settings = scalariformSettings ++ macroParadiseSettings ++ Seq(
    offline := false, // set to true to do work offline
    scalaVersion := "2.11.8",
    version := Version.appVersion,
    libraryDependencies ++= commonDependencies,
    scalacOptions ++= _scalacOptions,
    resolvers ++= _commonResolvers,
    javaOptions in Test ++= _javaTestOptions,
    testOptions in Test ++= _testOptions,
    parallelExecution in Test := false,
    EclipseKeys.skipParents in ThisBuild := false,
    sources in doc in Compile := List(),
    Keys.fork := false,
    aggregate in update := false,
    ScalariformKeys.preferences := ScalariformKeys.preferences.value
      .setPreference(DoubleIndentClassDeclaration, true),
    javaOptions in Test += "-Dlogger.resource=test-logger.xml",
    updateOptions := updateOptions.value.withCachedResolution(true),
    updateOptions := updateOptions.value.withLatestSnapshots(false) // sbt 0.13.6 can do a better job with SNAPSHOTS, but it's slower.
  )

}

object PlayGlobal {
  val _routesImport = Seq(
    "com.keepit.common.db.{ExternalId, Id, State, SequenceNumber}",
    "com.keepit.model._",
    "com.keepit.social._",
    "com.keepit.search.{ArticleSearchResult, SearchConfigExperiment}",
    "com.keepit.cortex.core._",
    "com.keepit.cortex.models.lda._",
    "com.keepit.common.mail.EmailAddress",
    "com.keepit.common.crypto._",
    "org.joda.time.DateTime",
    "com.keepit.common.time._",
    "com.keepit.shoebox.model.ids._",
    "com.keepit.rover.model._",
    "com.keepit.rover.article._",
    "com.keepit.common.store.ImageSize",
    "com.keepit.slack.models.{SlackTeamId,SlackUserId}",
    "com.keepit.discussion.Message"
  )

  val _templateImports = Seq(
    "com.keepit.common.db.{ExternalId, Id, State}",
    "com.keepit.model._",
    "com.keepit.social._",
    "com.keepit.search._",
    "com.keepit.common.mail._"
  )

  val settings = Seq(
    routesImport ++= _routesImport,
    TwirlKeys.templateImports ++= _templateImports
  )
}
