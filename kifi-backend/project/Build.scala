import sbt._
import Keys._
import play.Project._
import java.io.PrintWriter
import java.io.File
import java.util.Locale
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.DateTimeFormat
import com.typesafe.sbteclipse.core.EclipsePlugin.EclipseKeys

object ApplicationBuild extends Build {

  override def settings = super.settings ++ Seq(
      EclipseKeys.skipParents in ThisBuild := false)

    val appName         = "kifi-backend"

    val UTC = DateTimeZone.UTC
    val BUILD_DATETIME_FORMAT = DateTimeFormat.forPattern("yyyyMMdd-HHmm")
                                                 .withLocale(Locale.ENGLISH)
                                                 .withZone(UTC)
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

    val commonDependencies = Seq(
      jdbc,
      cache,
      "com.typesafe.play.plugins" %% "play-statsd" % "2.2.0" exclude("play", "*"),
      "com.typesafe" %% "play-plugins-mailer" % "2.2.0" exclude("play", "*"),
      "securesocial" %% "securesocial" % "master-20130808" exclude("play", "*"),
      "org.clapper" %% "grizzled-slf4j" % "1.0.1",
      "com.typesafe.akka" %% "akka-testkit" % "2.2.3"  exclude("play", "*"),
      "org.igniterealtime.smack" % "smackx-debug" % "3.2.1",
      "org.kevoree.extra.xmpp.lib" % "smack" % "3.2.2",
      "org.apache.httpcomponents" % "httpclient" % "4.3",
      "org.apache.tika" % "tika-parsers" % "1.3",
      "org.apache.commons" % "commons-math3" % "3.1.1",
      "commons-io" % "commons-io" % "2.4",
      "org.apache.zookeeper" % "zookeeper" % "3.4.5",
      "com.cybozu.labs" % "langdetect" % "1.1-20120112",
      "org.mindrot" % "jbcrypt" % "0.3m",
      "com.amazonaws" % "aws-java-sdk" % "1.3.20",
      "com.typesafe.slick" %% "slick" % "1.0.1" exclude("play", "*"),
      "net.sf.uadetector" % "uadetector-resources" % "2013.11",
      "com.newrelic.agent.java" % "newrelic-agent" % "2.18.0",
      "com.google.inject" % "guice" % "3.0",
      "com.google.inject.extensions" % "guice-multibindings" % "3.0",
      "net.codingwell" %% "scala-guice" % "3.0.2",
      "org.apache.lucene" % "lucene-core" % "4.2.1",
      "org.apache.lucene" % "lucene-analyzers-common" % "4.2.1",
      "org.apache.lucene" % "lucene-suggest" % "4.2.1",
      "org.imgscalr" % "imgscalr-lib" % "4.2",
      "us.theatr" %% "akka-quartz" % "0.2.0_42.1",
      "org.feijoas" %% "mango" % "0.10"
    ) map (_.excludeAll(
      ExclusionRule(organization = "com.cedarsoft"),
      ExclusionRule(organization = "javax.jms"),
      ExclusionRule(organization = "com.sun.jdmk"),
      ExclusionRule(organization = "com.sun.jmx"),
      ExclusionRule(organization = "org.jboss.netty"),
      ExclusionRule("org.scala-stm", "scala-stm_2.10.0")
    ))

    val searchDependencies = Seq(
      "edu.stanford.nlp.models" % "stanford-corenlp-models" % "1.3.5"
        from "http://scalasbt.artifactoryonline.com/scalasbt/repo/edu/stanford/nlp/stanford-corenlp/1.3.5/stanford-corenlp-1.3.5-models.jar",
      "edu.stanford.nlp" % "stanford-corenlp" % "1.3.5"
    )

    val sqldbDependencies = Seq(
      "mysql" % "mysql-connector-java" % "5.1.25"
    )

    val shoeboxDependencies = Seq(
      "javax.mail" % "mail" % "1.4.5",
      "com.typesafe.slick" %% "slick-testkit" % "1.0.1" exclude("play", "*"),
      "org.jsoup" % "jsoup" % "1.7.1"
    )

    val heimdalDependencies = Seq(
      "org.reactivemongo" %% "reactivemongo" % "0.10.0",
      "org.reactivemongo" %% "play2-reactivemongo" % "0.10.2",
      "com.maxmind.geoip2" % "geoip2" % "0.5.0",
      "com.mixpanel" % "mixpanel-java" % "1.2.1"
    ) map (_.excludeAll(
      ExclusionRule(organization = "org.slf4j"),
      ExclusionRule(organization = "ch.qos.logback")
    ))

    val abookDependencies = Seq()

    val scraperDependencies = Seq(
      "org.jsoup" % "jsoup" % "1.7.1"
    )

    val _scalacOptions = Seq("-unchecked", "-deprecation", "-feature", "-language:reflectiveCalls",
      "-language:implicitConversions", "-language:postfixOps", "-language:dynamics","-language:higherKinds",
      "-language:existentials", "-language:experimental.macros", "-Xmax-classfile-name", "140")

    val _routesImport = Seq(
      "com.keepit.common.db.{ExternalId, Id, State}",
      "com.keepit.model._",
      "com.keepit.social._",
      "com.keepit.search._"
    )

    val commonResolvers = Seq(
      Resolver.url("sbt-plugin-snapshots",
        new URL("http://repo.42go.com:4242/fortytwo/content/groups/public/"))(Resolver.ivyStylePatterns),
        // new URL("http://repo.scala-sbt.org/scalasbt/sbt-plugin-snapshots/"))(Resolver.ivyStylePatterns),
      // "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
      // "kevoree Repository" at "http://maven2.kevoree.org/release/",
      "FortyTwo Public Repository" at "http://repo.42go.com:4242/fortytwo/content/groups/public/",
      "FortyTwo Towel Repository" at "http://repo.42go.com:4242/fortytwo/content/repositories/towel"
      //for org.mongodb#casb
      // "snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
      // "releases"  at "https://oss.sonatype.org/content/groups/scala-tools",
      // "terracotta" at "http://www.terracotta.org/download/reflector/releases/",
      // "The Buzz Media Maven Repository" at "http://maven.thebuzzmedia.com"
    )

    val _templatesImport = Seq(
      "com.keepit.common.db.{ExternalId, Id, State}",
      "com.keepit.model._",
      "com.keepit.social._",
      "com.keepit.search._"
    )

    val javaTestOptions = Seq("-Xms512m", "-Xmx2g", "-XX:PermSize=256m", "-XX:MaxPermSize=512m")

    val _testOptions = Seq(
      Tests.Argument("sequential", "true"),
      Tests.Argument("threadsNb", "16"),
      Tests.Argument("showtimes", "true"),
      Tests.Argument("stopOnFail", "true"),
      Tests.Argument("failtrace", "true")
    )

    val commonSettings = Seq(
      scalacOptions ++= _scalacOptions,
      routesImport ++= _routesImport,
      resolvers ++= commonResolvers,
      templatesImport ++= _templatesImport,

      javaOptions in Test ++= javaTestOptions,
      parallelExecution in Test := false,
      testOptions in Test ++= _testOptions,
      EclipseKeys.skipParents in ThisBuild := false,
      sources in doc in Compile := List(),
      Keys.fork := false
    )

    lazy val macros = play.Project("macros", appVersion, commonDependencies, path = file("modules/macros")).settings(
      commonSettings: _*
    )

    lazy val common = play.Project("common", appVersion, commonDependencies, path = file("modules/common")).settings(
      commonSettings ++ Seq(
        javaOptions in Test += "-Dconfig.resource=application-dev.conf"
      ): _*
    ).dependsOn(macros)

    lazy val sqldb = play.Project("sqldb", appVersion, sqldbDependencies, path = file("modules/sqldb")).settings(
      commonSettings: _*
    ).dependsOn(common % "test->test;compile->compile").aggregate(common)

    val shoebox = play.Project("shoebox", appVersion, shoeboxDependencies, path = file("modules/shoebox")).settings(
      commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-shoebox.conf"): _*
    ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile").aggregate(common, sqldb)

    val search = play.Project("search", appVersion, searchDependencies, path = file("modules/search")).settings(
      commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-search.conf"): _*
    ).dependsOn(common % "test->test;compile->compile").aggregate(common)

    val eliza = play.Project("eliza", appVersion, Nil, path = file("modules/eliza")).settings(
      (commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-eliza.conf") ++ (routesImport ++= Seq("com.keepit.eliza._", "com.keepit.eliza.model._"))) : _*
    ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile").aggregate(common, sqldb)

    val heimdal = play.Project("heimdal", appVersion, heimdalDependencies, path=file("modules/heimdal")).settings(
      commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-heimdal.conf"): _*
    ).dependsOn(common % "test->test;compile->compile").aggregate(common)

    val abook = play.Project("abook", appVersion, abookDependencies, path=file("modules/abook")).settings(
      commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-abook.conf"): _*
    ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile").aggregate(common, sqldb)

    val scraper = play.Project("scraper", appVersion, scraperDependencies, path=file("modules/scraper")).settings(
      commonSettings ++ Seq(javaOptions in Test += "-Dconfig.resource=application-scraper.conf"): _*
    ).dependsOn(common % "test->test;compile->compile").aggregate(common)

    val aaaMain = play.Project(appName, appVersion).settings(
      commonSettings: _*
    ).dependsOn(common % "test->test;compile->compile", search % "test->test;compile->compile", shoebox % "test->test;compile->compile", eliza % "test->test;compile->compile", heimdal % "test->test;compile->compile", abook % "test->test;compile->compile", scraper % "test->test;compile->compile").aggregate(common, search, shoebox, eliza, heimdal, abook, scraper)

    override def rootProject = Some(aaaMain)
}
