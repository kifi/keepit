// Comment to get more information during initialization
logLevel := Level.Warn

// The Typesafe repository
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.2.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.3.0")

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.4")