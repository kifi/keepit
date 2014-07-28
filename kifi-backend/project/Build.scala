import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import sbt._
import Keys._
import play.Project._
import java.io.PrintWriter
import java.io.File
import java.util.Locale
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.DateTimeFormat
import com.typesafe.sbteclipse.core.EclipsePlugin.EclipseKeys
import com.typesafe.sbt.SbtScalariform._
import scalariform.formatter.preferences._

object ApplicationBuild extends Build {

  override def settings = super.settings ++ Seq(EclipseKeys.skipParents in ThisBuild := false)
  val appName         = "kifi-backend"

  val UTC = DateTimeZone.UTC
  val BUILD_DATETIME_FORMAT = DateTimeFormat.forPattern("yyyyMMdd-HHmm").withLocale(Locale.ENGLISH).withZone(UTC)
  val buildTime  = BUILD_DATETIME_FORMAT.print(new DateTime(UTC))
  val appVersion = "%s-%s-%s".format(buildTime,"git rev-parse --abbrev-ref HEAD".!!.trim, "git rev-parse --short HEAD".!!.trim)
  val now = DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z").withLocale(Locale.ENGLISH).withZone(UTC).print(new DateTime(UTC))

  def writeToFile(fileName: String, value: String) = {
    val file = new PrintWriter(new File(fileName))
    try { file.print(value) } finally { file.close() }
  }

  writeToFile("conf/app_version.txt", appVersion)
  writeToFile("modules/common/conf/app_version.txt", appVersion)
  writeToFile("conf/app_compilation_date.txt", now)
  writeToFile("modules/common/conf/app_compilation_date.txt", now)

  lazy val emojiLogs = logManager ~= { lm =>
    new LogManager {
      def apply(data: sbt.Settings[Scope], state: State, task: Def.ScopedKey[_], writer: java.io.PrintWriter) = {
        val l = lm.apply(data, state, task, writer)
        val FailuresErrors = "(?s).*(\\d+) failures?, (\\d+) errors?.*".r
        new Logger {
          def filter(s: String) = {
            val filtered = s.replace("\033[32m+\033[0m", "\u2705 ")
              .replace("\033[33mx\033[0m", "\u274C ")
              .replace("\033[31m!\033[0m", "\uD83D\uDCA5 ")
            filtered match {
              case FailuresErrors("0", "0") => filtered + " \uD83D\uDE04"
              case FailuresErrors(_, _) => filtered + " \uD83D\uDE22"
              case _ => filtered
            }
          }
          def log(level: Level.Value, message: => String) = l.log(level, filter(message))
          def success(message: => String) = l.success(message)
          def trace(t: => Throwable) = l.trace(t)

          override def ansiCodesSupported = l.ansiCodesSupported
        }
      }
    }
  }

  val angularDirectory = SettingKey[File]("angular-directory")

  private def cmd(name: String, command: String, base: File, namedArgs: List[String] = Nil): Command = {
    Command.args(name, "<" + name + "-command>") { (state, args) =>
      val exitCode = Process(command :: (namedArgs ++ args.toList), base).!;
      if (exitCode!=0) throw new Exception(s"Command '${(command :: (namedArgs ++ args.toList)).mkString(" ")}' failed with exit code $exitCode")
      state
    }
  }

  val commonDependencies = Seq(
    jdbc, // todo(andrew): move to sqldb when we discover a way to get Play to support multiple play.plugins files.
    cache,
    "com.typesafe.play.plugins" %% "play-statsd" % "2.2.0" exclude("play", "*"),
    "com.typesafe" %% "play-plugins-mailer" % "2.2.0" exclude("play", "*"),
    "securesocial" %% "securesocial" % "master-20130808" exclude("play", "*"),
    "org.clapper" %% "grizzled-slf4j" % "1.0.1",
    "com.typesafe.akka" %% "akka-testkit" % "2.2.3"  exclude("play", "*"),
    "org.apache.commons" % "commons-compress" % "1.4.1",
    "org.apache.commons" % "commons-math3" % "3.1.1",
    "commons-io" % "commons-io" % "2.4",
    "org.apache.zookeeper" % "zookeeper" % "3.4.5",
    "commons-codec" % "commons-codec" % "1.6",
    "com.cybozu.labs" % "langdetect" % "1.1-20120112", // todo(andrew): remove from common. make shared module between search and scraper.
    "org.mindrot" % "jbcrypt" % "0.3m",
    "com.amazonaws" % "aws-java-sdk" % "1.6.12",
    "com.kifi" % "franz_2.10" % "0.3.5",
    "net.sf.uadetector" % "uadetector-resources" % "2013.11",
    "com.google.inject" % "guice" % "3.0",
    "com.google.inject.extensions" % "guice-multibindings" % "3.0",
    "net.codingwell" %% "scala-guice" % "3.0.2",
    "org.imgscalr" % "imgscalr-lib" % "4.2",
    "us.theatr" %% "akka-quartz" % "0.2.0_42.1",
    "org.jsoup" % "jsoup" % "1.7.1",
    "org.bouncycastle" % "bcprov-jdk15on" % "1.50",
    "org.msgpack" %% "msgpack-scala" % "0.6.8",
    "com.kifi" %% "json-annotation" % "0.1"
  ) map (_.excludeAll(
    ExclusionRule(organization = "com.cedarsoft"),
    ExclusionRule(organization = "javax.jms"),
    ExclusionRule(organization = "com.sun.jdmk"),
    ExclusionRule(organization = "com.sun.jmx"),
    ExclusionRule(organization = "org.jboss.netty"),
    ExclusionRule("org.scala-stm", "scala-stm_2.10.0")
  ))

  lazy val searchDependencies = Seq(
    "edu.stanford.nlp.models" % "stanford-corenlp-models" % "1.3.5"
      from "http://scalasbt.artifactoryonline.com/scalasbt/repo/edu/stanford/nlp/stanford-corenlp/1.3.5/stanford-corenlp-1.3.5-models.jar",
    "edu.stanford.nlp" % "stanford-corenlp" % "1.3.5",
    "org.apache.lucene" % "lucene-core" % "4.7.0",
    "org.apache.lucene" % "lucene-analyzers-common" % "4.7.0",
    "org.apache.lucene" % "lucene-analyzers-kuromoji" % "4.7.0",
    "org.apache.lucene" % "lucene-suggest" % "4.7.0"
  )

  lazy val sqldbDependencies = Seq(
    "mysql" % "mysql-connector-java" % "5.1.25",
    "com.typesafe.slick" %% "slick" % "2.0.0" exclude("play", "*")
  )

  lazy val shoeboxDependencies = Seq(
    "javax.mail" % "mail" % "1.4.5",
    "com.typesafe.slick" %% "slick-testkit" % "2.0.0" exclude("play", "*"),
    "org.apache.poi" % "poi" % "3.8",
    "com.googlecode.mp4parser" % "isoparser" % "1.0-RC-1",
    "org.feijoas" %% "mango" % "0.10"
  )

  lazy val heimdalDependencies = Seq(
    "org.reactivemongo" %% "reactivemongo" % "0.10.0",
    "org.reactivemongo" %% "play2-reactivemongo" % "0.10.2",
    "com.maxmind.geoip2" % "geoip2" % "0.5.0",
    "com.mixpanel" % "mixpanel-java" % "1.2.1"
  ) map (_.excludeAll(
    ExclusionRule(organization = "org.slf4j"),
    ExclusionRule(organization = "ch.qos.logback")
  ))

  lazy val abookDependencies = Seq()

  lazy val scraperDependencies = Seq(
    "org.apache.lucene" % "lucene-analyzers-common" % "4.7.0",
    "org.apache.httpcomponents" % "httpclient" % "4.3.2",
    "org.apache.tika" % "tika-parsers" % "1.5"
  )

  lazy val cortexDependencies = Seq(
    // got this from http://grepcode.com/
    "edu.stanford.nlp.models" % "stanford-corenlp-models" % "3.2.0" from
      "http://repo1.maven.org/maven2/edu/stanford/nlp/stanford-corenlp/3.2.0/stanford-corenlp-3.2.0-models.jar",
    "edu.stanford.nlp" % "stanford-corenlp" % "3.2.0"
  )

  lazy val graphDependencies = Seq()

  lazy val curatorDependencies = Seq()

  lazy val _scalacOptions = Seq("-unchecked", "-deprecation", "-feature", "-language:reflectiveCalls",
    "-language:implicitConversions", "-language:postfixOps", "-language:dynamics","-language:higherKinds",
    "-language:existentials", "-language:experimental.macros", "-Xmax-classfile-name", "140")

  lazy val _routesImport = Seq(
    "com.keepit.common.db.{ExternalId, Id, State, SequenceNumber}",
    "com.keepit.model._",
    "com.keepit.social._",
    "com.keepit.search._",
    "com.keepit.cortex.core._",
    "com.keepit.cortex.models.lda._",
    "com.keepit.common.mail.EmailAddress",
    "com.keepit.common.crypto._"
  )

  lazy val commonResolvers = Seq(
    Resolver.url("sbt-plugin-snapshots",
      new URL("http://repo.42go.com:4242/fortytwo/content/groups/public/"))(Resolver.ivyStylePatterns),
    // new URL("http://repo.scala-sbt.org/scalasbt/sbt-plugin-snapshots/"))(Resolver.ivyStylePatterns),
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    // "kevoree Repository" at "http://maven2.kevoree.org/release/",
    "FortyTwo Public Repository" at "http://repo.42go.com:4242/fortytwo/content/groups/public/",
    "FortyTwo Towel Repository" at "http://repo.42go.com:4242/fortytwo/content/repositories/towel"
    //for org.mongodb#casb
    // "snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    // "releases"  at "https://oss.sonatype.org/content/groups/scala-tools",
    // "terracotta" at "http://www.terracotta.org/download/reflector/releases/",
    // "The Buzz Media Maven Repository" at "http://maven.thebuzzmedia.com"
  )

  lazy val _templatesImport = Seq(
    "com.keepit.common.db.{ExternalId, Id, State}",
    "com.keepit.model._",
    "com.keepit.social._",
    "com.keepit.search._"
  )

  lazy val javaTestOptions = Seq("-Xms512m", "-Xmx2g", "-XX:PermSize=256m", "-XX:MaxPermSize=512m")

  lazy val _testOptions = Seq(
    Tests.Argument("sequential", "true"),
    Tests.Argument("threadsNb", "16"),
    Tests.Argument("showtimes", "true"),
    Tests.Argument("stopOnFail", "true"),
    Tests.Argument("failtrace", "true")
  )

  lazy val macroParadiseSettings = Seq(
    resolvers += Resolver.sonatypeRepo("releases"),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full)
  )

  lazy val commonSettings = scalariformSettings ++ macroParadiseSettings ++ Seq(
    scalacOptions ++= _scalacOptions,
    routesImport ++= _routesImport,
    resolvers ++= commonResolvers,
    templatesImport ++= _templatesImport,

    javaOptions in Test ++= javaTestOptions,
    parallelExecution in Test := false,
    testOptions in Test ++= _testOptions,
    EclipseKeys.skipParents in ThisBuild := false,
    sources in doc in Compile := List(),
    Keys.fork := false,
    // Keys.fork in Test := false, // uncomment to hook debugger while running tests
    /*skip in update := true,
     *skip in update in (Compile, test) := true*/
    aggregate in update := false,
    emojiLogs,
    // incOptions := incOptions.value.withNameHashing(true) // see https://groups.google.com/forum/#!msg/play-framework/S_-wYW5Tcvw/OjJuB4iUwD8J
    ScalariformKeys.preferences := ScalariformKeys.preferences.value
      .setPreference(DoubleIndentClassDeclaration, true)
    //,     offline := true
  )

  lazy val macros = Project(id = s"macros", base = file("modules/macros")).settings(
    macroParadiseSettings ++ Seq(
      libraryDependencies += "org.scala-lang" % "scala-reflect" % "2.10.0",
      libraryDependencies ++= (
        if (scalaVersion.value.startsWith("2.10")) Seq("org.scalamacros" %% "quasiquotes" % "2.0.1")
        else Nil
      )
    ): _*
  )

  lazy val common = play.Project("common", appVersion, commonDependencies, path = file("modules/common")).settings(
    commonSettings ++ Seq(
      javaOptions in Test += "-Dconfig.resource=application-dev.conf"
    ): _*
  ).dependsOn(macros)

  lazy val sqldb = play.Project("sqldb", appVersion, sqldbDependencies, path = file("modules/sqldb")).settings(
    commonSettings: _*
  ).dependsOn(common % "test->test;compile->compile")

  lazy val shoebox = play.Project("shoebox", appVersion, shoeboxDependencies, path = file("modules/shoebox")).settings(
    commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-shoebox.conf"): _*
  ).settings(
    playAssetsDirectories <+= (baseDirectory in Compile)(_ / "angular"),
    angularDirectory <<= (baseDirectory in Compile) { _ / "angular" },
    commands <++= angularDirectory { base =>
      Seq("grunt", "bower", "npm").map(c => cmd("ng-" + c, c, base))
    },
    commands <+= angularDirectory { base => cmd("ng", "grunt", base, List("dev")) }
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val search = play.Project("search", appVersion, searchDependencies, path = file("modules/search")).settings(
    commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-search.conf"): _*
  ).dependsOn(common % "test->test;compile->compile")

  lazy val eliza = play.Project("eliza", appVersion, Nil, path = file("modules/eliza")).settings(
    (commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-eliza.conf") ++ (routesImport ++= Seq("com.keepit.eliza._", "com.keepit.eliza.model._"))) : _*
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val heimdal = play.Project("heimdal", appVersion, heimdalDependencies, path=file("modules/heimdal")).settings(
    commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-heimdal.conf"): _*
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val abook = play.Project("abook", appVersion, abookDependencies, path=file("modules/abook")).settings(
    commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-abook.conf"): _*
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val scraper = play.Project("scraper", appVersion, scraperDependencies, path=file("modules/scraper")).settings(
    commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-scraper.conf"): _*
  ).dependsOn(common % "test->test;compile->compile")

  lazy val cortex = play.Project("cortex", appVersion, cortexDependencies, path=file("modules/cortex")).settings(
    commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-cortex.conf"): _*
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val graph = play.Project("graph", appVersion, graphDependencies, path=file("modules/graph")).settings(
    commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-graph.conf"): _*
  ).dependsOn(common % "test->test;compile->compile")

  lazy val curator = play.Project("curator", appVersion, curatorDependencies, path=file("modules/curator")).settings(
    commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-curator.conf"): _*
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val kifiBackend = play.Project(appName, "0.42").settings(commonSettings: _*)
    .settings(
    aggregate in update := false,
    angularDirectory <<= (baseDirectory in Compile) { _ / "modules/shoebox/angular" },
    commands <++= angularDirectory { base =>
      Seq("grunt", "bower", "npm").map(c => cmd("ng-" + c, c, base))
    },
    commands <+= angularDirectory { base => cmd("ng", "grunt", base, List("dev")) }
  )
    .dependsOn(
    common % "test->test;compile->compile",
    shoebox % "test->test;compile->compile",
    search % "test->test;compile->compile",
    eliza % "test->test;compile->compile",
    heimdal % "test->test;compile->compile",
    abook % "test->test;compile->compile",
    scraper % "test->test;compile->compile",
    cortex % "test->test;compile->compile",
    graph % "test->test;compile->compile",
    curator % "test->test;compile->compile")
    .aggregate(common, shoebox, search, eliza, heimdal, abook, scraper, sqldb, cortex, graph, curator)

  lazy val distProject = Project(id = "dist", base = file("./.dist"))
    .settings(aggregate in update := false)
    .aggregate(search, shoebox, eliza, heimdal, abook, scraper, cortex, graph, curator)

  override def rootProject = Some(kifiBackend)
}
