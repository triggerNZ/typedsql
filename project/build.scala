import sbt._, Keys._

import au.com.cba.omnia.uniform.core.standard.StandardProjectPlugin._
import au.com.cba.omnia.uniform.core.version.UniqueVersionPlugin._
import au.com.cba.omnia.uniform.dependency.UniformDependencyPlugin._, depend.versions
import au.com.cba.omnia.uniform.thrift.UniformThriftPlugin._
import au.com.cba.omnia.uniform.assembly.UniformAssemblyPlugin._

import com.dancingrobot84.sbtidea.Keys._
import com.dancingrobot84.sbtidea.SbtIdeaPlugin

import sbtassembly.AssemblyPlugin.autoImport._

object build extends Build {
  val maestroVersion = "2.20.0-20160520031836-e06bc75"

  lazy val standardSettings =
    Defaults.coreDefaultSettings ++
    uniformPublicDependencySettings ++
    strictDependencySettings ++
    Seq(
      concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
      dependencyOverrides += "com.chuusai" %% "shapeless" % "2.2.5" //until maestro is updated
    )

  lazy val macroBuildSettings = Seq(
    libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-reflect" % _),
    libraryDependencies ++= {
      if (scalaBinaryVersion.value == "2.10") Seq(
        compilerPlugin("org.scalamacros" % "paradise" % "2.0.0" cross CrossVersion.full),
        "org.scalamacros" %% "quasiquotes" % "2.0.0" cross CrossVersion.binary
      ) else Nil
    },
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
  )

  lazy val all = Project(
    id = "all"
    , base = file(".")
    , settings =
      standardSettings
        ++ uniform.project("typedsql-all", "com.rouesnel.typedsql.all")
        ++ Seq(
        publishArtifact := false,
        onLoad in Global := ((s: State) => { "updateIdea" :: s}) compose (onLoad in Global).value
      )
    , aggregate = Seq(core, intellij, server, api)
  )

  lazy val core = Project(
    id = "core"
    , base = file("core")
    , settings =
      standardSettings
        ++ uniform.project("typedsql-core", "com.rouesnel.typedsql")
        ++ uniformThriftSettings
        ++ macroBuildSettings
        ++ Seq(
        //conflictManager := ConflictManager.default,
        libraryDependencies ++=
          depend.hadoopClasspath ++
          depend.omnia("maestro-test", maestroVersion, "test") ++
          depend.omnia("beeswax", "0.1.1-20160120052815-6bf77d2") ++
          depend.parquet() ++
          depend.testing() ++
          depend.logging() ++
          depend.hadoop() ++
          depend.hive() ++
          depend.omnia("maestro-test", maestroVersion, "test") ++
          Seq(
            "au.com.cba.omnia" %% "thermometer-hive" %  "1.4.2-20160414053315-99c196d",
            "ch.qos.logback"    % "logback-classic"  % "1.0.13"
          )
      )
  )

  lazy val server = Project(
    id = "server",
    base = file("server"),
    settings = standardSettings ++
      uniform.project("typedsql-server", "com.rouesnel.typedsql.server") ++
      Seq(
        conflictManager := ConflictManager.default,
        libraryDependencies ++=
          Seq(
            "org.apache.hadoop"  % "hadoop-client" % depend.versions.hadoop,
            "org.apache.hive"    % "hive-exec"     % depend.versions.hive,
            "au.com.cba.omnia"  %% "thermometer-hive" %  "1.4.2-20160414053315-99c196d",
            "com.typesafe.akka" %% "akka-actor" % "2.4.8",
            "com.typesafe.akka" %% "akka-remote" % "2.4.8"
          ),
        // Exclude the datanucleus jars.
        assemblyExcludedJars in assembly := {
          val cp = (fullClasspath in assembly).value
          cp.filter(_.data.getPath.contains("org.datanucleus"))
        },
        assemblyMergeStrategy in assembly := {
          case x if Assembly.isConfigFile(x) =>
            MergeStrategy.concat
          case PathList(ps @ _*) if Assembly.isReadme(ps.last) || Assembly.isLicenseFile(ps.last) =>
            MergeStrategy.rename
          case PathList("META-INF", xs @ _*) =>
            (xs map {_.toLowerCase}) match {
              case ("manifest.mf" :: Nil) | ("index.list" :: Nil) | ("dependencies" :: Nil) =>
                MergeStrategy.discard
              case ps @ (x :: xs) if ps.last.endsWith(".sf") || ps.last.endsWith(".dsa") =>
                MergeStrategy.discard
              case "plexus" :: xs =>
                MergeStrategy.discard
              case "services" :: xs =>
                MergeStrategy.filterDistinctLines
              case ("spring.schemas" :: Nil) | ("spring.handlers" :: Nil) =>
                MergeStrategy.filterDistinctLines
              case _ => MergeStrategy.first
            }
          case _ => MergeStrategy.first
        },
        assembly := {
          val assembledJar = assembly.value
          val cp = (fullClasspath in assembly).value
          cp.filter(_.data.getPath.contains("org.datanucleus")).foreach(jar => {
            println(s"Copying non-assemble-able jar: ${jar.data.getName}")
            IO.copyFile(jar.data, new File(assembledJar.getParentFile, jar.data.getName))
          })
          assembledJar
        }
      )
  ).dependsOn(core, api)

  lazy val api = Project(
    id = "api",
    base = file("api"),
    settings = standardSettings ++
      uniform.project("typedsql-api", "com.rouesnel.typedsql.api")
  )

  lazy val intellij = Project(
    id = "intellij"
    , base = file("intellij")
    , settings = standardSettings ++ List(
        assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false),
        libraryDependencies ++= Seq(
          "com.typesafe.akka" %% "akka-actor" % "2.4.8",
          "com.typesafe.akka" %% "akka-remote" % "2.4.8"
        ),
        ideaInternalPlugins := Seq(),
        ideaExternalPlugins := Seq(IdeaPlugin.Zip("scala-plugin", url("https://plugins.jetbrains.com/files/1347/27087/scala-inte llij-bin-2016.2.0.zip"))),
        assemblyExcludedJars in assembly <<= ideaFullJars,
        assemblyMergeStrategy in assembly := {
          case x if Assembly.isConfigFile(x) =>
            MergeStrategy.concat
          case PathList(ps @ _*) if Assembly.isReadme(ps.last) || Assembly.isLicenseFile(ps.last) =>
            MergeStrategy.rename
          case PathList("META-INF", xs @ _*) =>
            (xs map {_.toLowerCase}) match {
              case ("manifest.mf" :: Nil) | ("index.list" :: Nil) | ("dependencies" :: Nil) =>
                MergeStrategy.discard
              case ps @ (x :: xs) if ps.last.endsWith(".sf") || ps.last.endsWith(".dsa") =>
                MergeStrategy.discard
              case "plexus" :: xs =>
                MergeStrategy.discard
              case "services" :: xs =>
                MergeStrategy.filterDistinctLines
              case ("spring.schemas" :: Nil) | ("spring.handlers" :: Nil) =>
                MergeStrategy.filterDistinctLines
              case _ => MergeStrategy.first
            }
          case _ => MergeStrategy.first
        },
        ideaBuild := "2016.2",
        scalaVersion := "2.11.7",
        aggregate in updateIdea := false,
        unmanagedJars in Compile <<= ideaFullJars,
        unmanagedJars in Compile += file(System.getProperty("java.home")).getParentFile / "lib" / "tools.jar",
        packagePlugin := {
          val pluginName = "typedsql-idea"
          val ivyLocal = ivyPaths.value.ivyHome.getOrElse(file(System.getProperty("user.home")) / ".ivy2") / "local"
          val pluginJar = assembly.value
          val sources = Seq(
            pluginJar               -> s"$pluginName/lib/${pluginJar.getName}",
            (assembly in cli).value -> s"$pluginName/bin/cli.jar"
          ) ++ (assemblyExcludedJars in (cli, assembly)).value.map(jar => {
            jar.data -> s"$pluginName/bin/${jar.data.getName}"
          })
          val out = target.value / s"$pluginName-plugin.zip"
          IO.zip(sources, out)
          out
        }
    )
  ).enablePlugins(SbtIdeaPlugin).dependsOn(api)
  lazy val packagePlugin = TaskKey[File]("package-plugin", "Create plugin's zip file ready to load into IDEA")

  lazy val ideaRunner: Project = project.in(file("ideaRunner"))
    .dependsOn(intellij % Provided)
    .settings(
      name := "ideaRunner",
      version := "1.0",
      scalaVersion := "2.11.7",
      autoScalaLibrary := false,
      unmanagedJars in Compile <<= ideaMainJars.in(intellij),
      unmanagedJars in Compile += file(System.getProperty("java.home")).getParentFile / "lib" / "tools.jar"
    )
}