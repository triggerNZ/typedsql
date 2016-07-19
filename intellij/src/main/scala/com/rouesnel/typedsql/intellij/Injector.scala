package com.rouesnel.typedsql.intellij

import java.io.File

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask

import com.intellij.openapi.diagnostic.Logger

import com.typesafe.config._

import org.jetbrains.plugins.scala.lang.psi.api.base.ScLiteral
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunctionDefinition, ScValue}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScExtendsBlock, ScTemplateBody}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.SyntheticMembersInjector

import com.rouesnel.typedsql.api._

object CliHelper {
  import scala.sys.process._

  def startCliProcess(): Process = {
    val pathToCurrentClass = new File(
      classOf[Injector].getResource('/' + classOf[Injector].getName.replace('.', '/') + ".class")
          .getPath
          .drop(5) // "file:" prefix
    )

    val pathToCurrentJar = pathToCurrentClass.toString.split('!').head

    // Path to the plugin location
    val pluginLocation =
      new File(pathToCurrentJar) // <plugin location>/lib/<jar name>.jar
        .getParentFile // <plugin location>/lib
        .getParentFile // <plugin location>


    val binDirectory = new File(pluginLocation, "bin")

    val jars = binDirectory.listFiles().toList

    s"java -cp ${jars.mkString(":")} com.rouesnel.typedsql.intellij.server.CompilationServer".run()
  }
}

class Injector extends SyntheticMembersInjector {
  import scala.concurrent.ExecutionContext.Implicits.global

  val LOG = Logger.getInstance(getClass)
  def log(str: String) = LOG.warn(str)
  log("INITIALIZING INJECTOR!")

  val process = CliHelper.startCliProcess()

  // The injector runs a separate JVM to perform Hive schema compilation (prevents classpath issues).
  // Communication is performed using Akka Remoting.
  def useAkkaClassloader[T](f: => T): T = {
    // Replace the classloader with the correct path.
    val contextClassLoader = Thread.currentThread().getContextClassLoader
    Thread.currentThread().setContextClassLoader(classOf[Actor].getClassLoader)
    // Run the computation
    val result = try {
      Right(f)
    } catch {
      case ex: Exception => Left(ex)
    }
    // Reset the contextClassLoader.
    Thread.currentThread().setContextClassLoader(contextClassLoader)
    // Return the result
    result match {
      case Right(value) => value
      case Left(error) => throw error
    }
  }

  log("Starting Actor System")
  val system = useAkkaClassloader { ActorSystem("intellij", ConfigFactory.parseString(
    """
      |akka {
      |  version = "2.4.8"
      |  loglevel = "INFO"
      |  actor {
      |    provider = "akka.remote.RemoteActorRefProvider"
      |  }
      |  remote {
      |    enabled-transports = ["akka.remote.netty.tcp"]
      |    netty.tcp {
      |      hostname = "127.0.0.1"
      |      port = 39115
      |    }
      |    log-sent-messages = on
      |    log-received-messages = on
      |  }
      |}
    """.stripMargin).withFallback(DefaultAkkaConfig.config).resolve()) }
  val remoteActor = system.actorSelection("akka.tcp://typedsql-intellij-server@127.0.0.1:39114/user/listener")
  import akka.util.Timeout, Timeout._
  implicit val timeout: Timeout = 15 seconds

  override def injectInners(source: ScTypeDefinition): Seq[String] = {
    source match {
      case o: ScObject if o.hasAnnotation("com.rouesnel.typedsql.SqlQuery").isDefined => {
        val maybeSqlText =
          o.children.collect({
            case eb: ScExtendsBlock => {
              eb.children.collect({
                case td: ScTemplateBody => {
                  td.children.collect({
                    case fd: ScFunctionDefinition => {
                      fd.children.collect({
                        case l: ScLiteral => l.getText
                      }).toList
                    }
                  }).toList
                }
              }).toList
            }
          }).toList.flatten.flatten.flatten.headOption
        maybeSqlText match {
          case None => {
            log("Didn't find a SQL query?")
            Nil
          }
          case Some(sqlText) => {
            log(s"Found: $sqlText")
            log(s"Compiling query...")
            val result = Await.result(remoteActor.ask(CompilationRequest(sqlText)), 1.seconds)
            result match {
              case CompilationResponse(code) => List(code)
              case other => {
                log(s"Expected compilation response but got: $other")
                Nil
              }
            }
          }
        }
      }
      case _ => Nil
    }
  }
}

/**
 * The default Akka config is injected here programmatically because there are ample opportunities
 * for the Java resources to get mangled (e.g. on building a fat JAR or by IntelliJ's plugin
 * loader).
 */
object DefaultAkkaConfig {
  def config = ConfigFactory.parseString(text)
  def text =
    """
      |akka {
      |  # Home directory of Akka, modules in the deploy directory will be loaded
      |  home = ""
      |
      |  # Loggers to register at boot time (akka.event.Logging$DefaultLogger logs
      |  # to STDOUT)
      |  loggers = ["akka.event.Logging$DefaultLogger"]
      |
      |  # Filter of log events that is used by the LoggingAdapter before
      |  # publishing log events to the eventStream. It can perform
      |  # fine grained filtering based on the log source. The default
      |  # implementation filters on the `loglevel`.
      |  # FQCN of the LoggingFilter. The Class of the FQCN must implement
      |  # akka.event.LoggingFilter and have a public constructor with
      |  # (akka.actor.ActorSystem.Settings, akka.event.EventStream) parameters.
      |  logging-filter = "akka.event.DefaultLoggingFilter"
      |
      |  # Specifies the default loggers dispatcher
      |  loggers-dispatcher = "akka.actor.default-dispatcher"
      |
      |  # Loggers are created and registered synchronously during ActorSystem
      |  # start-up, and since they are actors, this timeout is used to bound the
      |  # waiting time
      |  logger-startup-timeout = 5s
      |
      |  # Log level used by the configured loggers (see "loggers") as soon
      |  # as they have been started; before that, see "stdout-loglevel"
      |  # Options: OFF, ERROR, WARNING, INFO, DEBUG
      |  loglevel = "INFO"
      |
      |  # Log level for the very basic logger activated during ActorSystem startup.
      |  # This logger prints the log messages to stdout (System.out).
      |  # Options: OFF, ERROR, WARNING, INFO, DEBUG
      |  stdout-loglevel = "WARNING"
      |
      |  # Log the complete configuration at INFO level when the actor system is started.
      |  # This is useful when you are uncertain of what configuration is used.
      |  log-config-on-start = off
      |
      |  # Log at info level when messages are sent to dead letters.
      |  # Possible values:
      |  # on: all dead letters are logged
      |  # off: no logging of dead letters
      |  # n: positive integer, number of dead letters that will be logged
      |  log-dead-letters = 10
      |
      |  # Possibility to turn off logging of dead letters while the actor system
      |  # is shutting down. Logging is only done when enabled by 'log-dead-letters'
      |  # setting.
      |  log-dead-letters-during-shutdown = on
      |
      |  # List FQCN of extensions which shall be loaded at actor system startup.
      |  # Library extensions are regular extensions that are loaded at startup and are
      |  # available for third party library authors to enable auto-loading of extensions when
      |  # present on the classpath. This is done by appending entries:
      |  # 'library-extensions += "Extension"' in the library `reference.conf`.
      |  #
      |  # Should not be set by end user applications in 'application.conf', use the extensions property for that
      |  #
      |  library-extensions = ${?akka.library-extensions} []
      |
      |  # List FQCN of extensions which shall be loaded at actor system startup.
      |  # Should be on the format: 'extensions = ["foo", "bar"]' etc.
      |  # See the Akka Documentation for more info about Extensions
      |  extensions = []
      |
      |  # Toggles whether threads created by this ActorSystem should be daemons or not
      |  daemonic = off
      |
      |  # JVM shutdown, System.exit(-1), in case of a fatal error,
      |  # such as OutOfMemoryError
      |  jvm-exit-on-fatal-error = on
      |
      |  actor {
      |
      |    # FQCN of the ActorRefProvider to be used; the below is the built-in default,
      |    # another one is akka.remote.RemoteActorRefProvider in the akka-remote bundle.
      |    provider = "akka.actor.LocalActorRefProvider"
      |
      |    # The guardian "/user" will use this class to obtain its supervisorStrategy.
      |    # It needs to be a subclass of akka.actor.SupervisorStrategyConfigurator.
      |    # In addition to the default there is akka.actor.StoppingSupervisorStrategy.
      |    guardian-supervisor-strategy = "akka.actor.DefaultSupervisorStrategy"
      |
      |    # Timeout for ActorSystem.actorOf
      |    creation-timeout = 20s
      |
      |    # Serializes and deserializes (non-primitive) messages to ensure immutability,
      |    # this is only intended for testing.
      |    serialize-messages = off
      |
      |    # Serializes and deserializes creators (in Props) to ensure that they can be
      |    # sent over the network, this is only intended for testing. Purely local deployments
      |    # as marked with deploy.scope == LocalScope are exempt from verification.
      |    serialize-creators = off
      |
      |    # Timeout for send operations to top-level actors which are in the process
      |    # of being started. This is only relevant if using a bounded mailbox or the
      |    # CallingThreadDispatcher for a top-level actor.
      |    unstarted-push-timeout = 10s
      |
      |    typed {
      |      # Default timeout for typed actor methods with non-void return type
      |      timeout = 5s
      |    }
      |
      |    # Mapping between ´deployment.router' short names to fully qualified class names
      |    router.type-mapping {
      |      from-code = "akka.routing.NoRouter"
      |      round-robin-pool = "akka.routing.RoundRobinPool"
      |      round-robin-group = "akka.routing.RoundRobinGroup"
      |      random-pool = "akka.routing.RandomPool"
      |      random-group = "akka.routing.RandomGroup"
      |      balancing-pool = "akka.routing.BalancingPool"
      |      smallest-mailbox-pool = "akka.routing.SmallestMailboxPool"
      |      broadcast-pool = "akka.routing.BroadcastPool"
      |      broadcast-group = "akka.routing.BroadcastGroup"
      |      scatter-gather-pool = "akka.routing.ScatterGatherFirstCompletedPool"
      |      scatter-gather-group = "akka.routing.ScatterGatherFirstCompletedGroup"
      |      tail-chopping-pool = "akka.routing.TailChoppingPool"
      |      tail-chopping-group = "akka.routing.TailChoppingGroup"
      |      consistent-hashing-pool = "akka.routing.ConsistentHashingPool"
      |      consistent-hashing-group = "akka.routing.ConsistentHashingGroup"
      |    }
      |
      |    deployment {
      |
      |      # deployment id pattern - on the format: /parent/child etc.
      |      default {
      |
      |        # The id of the dispatcher to use for this actor.
      |        # If undefined or empty the dispatcher specified in code
      |        # (Props.withDispatcher) is used, or default-dispatcher if not
      |        # specified at all.
      |        dispatcher = ""
      |
      |        # The id of the mailbox to use for this actor.
      |        # If undefined or empty the default mailbox of the configured dispatcher
      |        # is used or if there is no mailbox configuration the mailbox specified
      |        # in code (Props.withMailbox) is used.
      |        # If there is a mailbox defined in the configured dispatcher then that
      |        # overrides this setting.
      |        mailbox = ""
      |
      |        # routing (load-balance) scheme to use
      |        # - available: "from-code", "round-robin", "random", "smallest-mailbox",
      |        #              "scatter-gather", "broadcast"
      |        # - or:        Fully qualified class name of the router class.
      |        #              The class must extend akka.routing.CustomRouterConfig and
      |        #              have a public constructor with com.typesafe.config.Config
      |        #              and optional akka.actor.DynamicAccess parameter.
      |        # - default is "from-code";
      |        # Whether or not an actor is transformed to a Router is decided in code
      |        # only (Props.withRouter). The type of router can be overridden in the
      |        # configuration; specifying "from-code" means that the values specified
      |        # in the code shall be used.
      |        # In case of routing, the actors to be routed to can be specified
      |        # in several ways:
      |        # - nr-of-instances: will create that many children
      |        # - routees.paths: will route messages to these paths using ActorSelection,
      |        #   i.e. will not create children
      |        # - resizer: dynamically resizable number of routees as specified in
      |        #   resizer below
      |        router = "from-code"
      |
      |        # number of children to create in case of a router;
      |        # this setting is ignored if routees.paths is given
      |        nr-of-instances = 1
      |
      |        # within is the timeout used for routers containing future calls
      |        within = 5 seconds
      |
      |        # number of virtual nodes per node for consistent-hashing router
      |        virtual-nodes-factor = 10
      |
      |        tail-chopping-router {
      |          # interval is duration between sending message to next routee
      |          interval = 10 milliseconds
      |        }
      |
      |        routees {
      |          # Alternatively to giving nr-of-instances you can specify the full
      |          # paths of those actors which should be routed to. This setting takes
      |          # precedence over nr-of-instances
      |          paths = []
      |        }
      |
      |        # To use a dedicated dispatcher for the routees of the pool you can
      |        # define the dispatcher configuration inline with the property name
      |        # 'pool-dispatcher' in the deployment section of the router.
      |        # For example:
      |        # pool-dispatcher {
      |        #   fork-join-executor.parallelism-min = 5
      |        #   fork-join-executor.parallelism-max = 5
      |        # }
      |
      |        # Routers with dynamically resizable number of routees; this feature is
      |        # enabled by including (parts of) this section in the deployment
      |        resizer {
      |
      |          enabled = off
      |
      |          # The fewest number of routees the router should ever have.
      |          lower-bound = 1
      |
      |          # The most number of routees the router should ever have.
      |          # Must be greater than or equal to lower-bound.
      |          upper-bound = 10
      |
      |          # Threshold used to evaluate if a routee is considered to be busy
      |          # (under pressure). Implementation depends on this value (default is 1).
      |          # 0:   number of routees currently processing a message.
      |          # 1:   number of routees currently processing a message has
      |          #      some messages in mailbox.
      |          # > 1: number of routees with at least the configured pressure-threshold
      |          #      messages in their mailbox. Note that estimating mailbox size of
      |          #      default UnboundedMailbox is O(N) operation.
      |          pressure-threshold = 1
      |
      |          # Percentage to increase capacity whenever all routees are busy.
      |          # For example, 0.2 would increase 20% (rounded up), i.e. if current
      |          # capacity is 6 it will request an increase of 2 more routees.
      |          rampup-rate = 0.2
      |
      |          # Minimum fraction of busy routees before backing off.
      |          # For example, if this is 0.3, then we'll remove some routees only when
      |          # less than 30% of routees are busy, i.e. if current capacity is 10 and
      |          # 3 are busy then the capacity is unchanged, but if 2 or less are busy
      |          # the capacity is decreased.
      |          # Use 0.0 or negative to avoid removal of routees.
      |          backoff-threshold = 0.3
      |
      |          # Fraction of routees to be removed when the resizer reaches the
      |          # backoffThreshold.
      |          # For example, 0.1 would decrease 10% (rounded up), i.e. if current
      |          # capacity is 9 it will request an decrease of 1 routee.
      |          backoff-rate = 0.1
      |
      |          # Number of messages between resize operation.
      |          # Use 1 to resize before each message.
      |          messages-per-resize = 10
      |        }
      |
      |        # Routers with dynamically resizable number of routees based on
      |        # performance metrics.
      |        # This feature is enabled by including (parts of) this section in
      |        # the deployment, cannot be enabled together with default resizer.
      |        optimal-size-exploring-resizer {
      |
      |          enabled = off
      |
      |          # The fewest number of routees the router should ever have.
      |          lower-bound = 1
      |
      |          # The most number of routees the router should ever have.
      |          # Must be greater than or equal to lower-bound.
      |          upper-bound = 10
      |
      |          # probability of doing a ramping down when all routees are busy
      |          # during exploration.
      |          chance-of-ramping-down-when-full = 0.2
      |
      |          # Interval between each resize attempt
      |          action-interval = 5s
      |
      |          # If the routees have not been fully utilized (i.e. all routees busy)
      |          # for such length, the resizer will downsize the pool.
      |          downsize-after-underutilized-for = 72h
      |
      |          # Duration exploration, the ratio between the largest step size and
      |          # current pool size. E.g. if the current pool size is 50, and the
      |          # explore-step-size is 0.1, the maximum pool size change during
      |          # exploration will be +- 5
      |          explore-step-size = 0.1
      |
      |          # Probabily of doing an exploration v.s. optmization.
      |          chance-of-exploration = 0.4
      |
      |          # When downsizing after a long streak of underutilization, the resizer
      |          # will downsize the pool to the highest utiliziation multiplied by a
      |          # a downsize rasio. This downsize ratio determines the new pools size
      |          # in comparison to the highest utilization.
      |          # E.g. if the highest utilization is 10, and the down size ratio
      |          # is 0.8, the pool will be downsized to 8
      |          downsize-ratio = 0.8
      |
      |          # When optimizing, the resizer only considers the sizes adjacent to the
      |          # current size. This number indicates how many adjacent sizes to consider.
      |          optimization-range = 16
      |
      |          # The weight of the latest metric over old metrics when collecting
      |          # performance metrics.
      |          # E.g. if the last processing speed is 10 millis per message at pool
      |          # size 5, and if the new processing speed collected is 6 millis per
      |          # message at pool size 5. Given a weight of 0.3, the metrics
      |          # representing pool size 5 will be 6 * 0.3 + 10 * 0.7, i.e. 8.8 millis
      |          # Obviously, this number should be between 0 and 1.
      |          weight-of-latest-metric = 0.5
      |        }
      |      }
      |
      |      /IO-DNS/inet-address {
      |        mailbox = "unbounded"
      |        router = "consistent-hashing-pool"
      |        nr-of-instances = 4
      |      }
      |    }
      |
      |    default-dispatcher {
      |      # Must be one of the following
      |      # Dispatcher, PinnedDispatcher, or a FQCN to a class inheriting
      |      # MessageDispatcherConfigurator with a public constructor with
      |      # both com.typesafe.config.Config parameter and
      |      # akka.dispatch.DispatcherPrerequisites parameters.
      |      # PinnedDispatcher must be used together with executor=thread-pool-executor.
      |      type = "Dispatcher"
      |
      |      # Which kind of ExecutorService to use for this dispatcher
      |      # Valid options:
      |      #  - "default-executor" requires a "default-executor" section
      |      #  - "fork-join-executor" requires a "fork-join-executor" section
      |      #  - "thread-pool-executor" requires a "thread-pool-executor" section
      |      #  - A FQCN of a class extending ExecutorServiceConfigurator
      |      executor = "default-executor"
      |
      |      # This will be used if you have set "executor = "default-executor"".
      |      # If an ActorSystem is created with a given ExecutionContext, this
      |      # ExecutionContext will be used as the default executor for all
      |      # dispatchers in the ActorSystem configured with
      |      # executor = "default-executor". Note that "default-executor"
      |      # is the default value for executor, and therefore used if not
      |      # specified otherwise. If no ExecutionContext is given,
      |      # the executor configured in "fallback" will be used.
      |      default-executor {
      |        fallback = "fork-join-executor"
      |      }
      |
      |      # This will be used if you have set "executor = "fork-join-executor""
      |      # Underlying thread pool implementation is scala.concurrent.forkjoin.ForkJoinPool
      |      fork-join-executor {
      |        # Min number of threads to cap factor-based parallelism number to
      |        parallelism-min = 8
      |
      |        # The parallelism factor is used to determine thread pool size using the
      |        # following formula: ceil(available processors * factor). Resulting size
      |        # is then bounded by the parallelism-min and parallelism-max values.
      |        parallelism-factor = 3.0
      |
      |        # Max number of threads to cap factor-based parallelism number to
      |        parallelism-max = 64
      |
      |        # Setting to "FIFO" to use queue like peeking mode which "poll" or "LIFO" to use stack
      |        # like peeking mode which "pop".
      |        task-peeking-mode = "FIFO"
      |      }
      |
      |      # This will be used if you have set "executor = "thread-pool-executor""
      |      # Underlying thread pool implementation is java.util.concurrent.ThreadPoolExecutor
      |      thread-pool-executor {
      |        # Keep alive time for threads
      |        keep-alive-time = 60s
      |
      |        # Define a fixed thread pool size with this property. The corePoolSize
      |        # and the maximumPoolSize of the ThreadPoolExecutor will be set to this
      |        # value, if it is defined. Then the other pool-size properties will not
      |        # be used.
      |        #
      |        # Valid values are: `off` or a positive integer.
      |        fixed-pool-size = off
      |
      |        # Min number of threads to cap factor-based corePoolSize number to
      |        core-pool-size-min = 8
      |
      |        # The core-pool-size-factor is used to determine corePoolSize of the
      |        # ThreadPoolExecutor using the following formula:
      |        # ceil(available processors * factor).
      |        # Resulting size is then bounded by the core-pool-size-min and
      |        # core-pool-size-max values.
      |        core-pool-size-factor = 3.0
      |
      |        # Max number of threads to cap factor-based corePoolSize number to
      |        core-pool-size-max = 64
      |
      |        # Minimum number of threads to cap factor-based maximumPoolSize number to
      |        max-pool-size-min = 8
      |
      |        # The max-pool-size-factor is used to determine maximumPoolSize of the
      |        # ThreadPoolExecutor using the following formula:
      |        # ceil(available processors * factor)
      |        # The maximumPoolSize will not be less than corePoolSize.
      |        # It is only used if using a bounded task queue.
      |        max-pool-size-factor  = 3.0
      |
      |        # Max number of threads to cap factor-based maximumPoolSize number to
      |        max-pool-size-max = 64
      |
      |        # Specifies the bounded capacity of the task queue (< 1 == unbounded)
      |        task-queue-size = -1
      |
      |        # Specifies which type of task queue will be used, can be "array" or
      |        # "linked" (default)
      |        task-queue-type = "linked"
      |
      |        # Allow core threads to time out
      |        allow-core-timeout = on
      |      }
      |
      |      # How long time the dispatcher will wait for new actors until it shuts down
      |      shutdown-timeout = 1s
      |
      |      # Throughput defines the number of messages that are processed in a batch
      |      # before the thread is returned to the pool. Set to 1 for as fair as possible.
      |      throughput = 5
      |
      |      # Throughput deadline for Dispatcher, set to 0 or negative for no deadline
      |      throughput-deadline-time = 0ms
      |
      |      # For BalancingDispatcher: If the balancing dispatcher should attempt to
      |      # schedule idle actors using the same dispatcher when a message comes in,
      |      # and the dispatchers ExecutorService is not fully busy already.
      |      attempt-teamwork = on
      |
      |      # If this dispatcher requires a specific type of mailbox, specify the
      |      # fully-qualified class name here; the actually created mailbox will
      |      # be a subtype of this type. The empty string signifies no requirement.
      |      mailbox-requirement = ""
      |    }
      |
      |    default-mailbox {
      |      # FQCN of the MailboxType. The Class of the FQCN must have a public
      |      # constructor with
      |      # (akka.actor.ActorSystem.Settings, com.typesafe.config.Config) parameters.
      |      mailbox-type = "akka.dispatch.UnboundedMailbox"
      |
      |      # If the mailbox is bounded then it uses this setting to determine its
      |      # capacity. The provided value must be positive.
      |      # NOTICE:
      |      # Up to version 2.1 the mailbox type was determined based on this setting;
      |      # this is no longer the case, the type must explicitly be a bounded mailbox.
      |      mailbox-capacity = 1000
      |
      |      # If the mailbox is bounded then this is the timeout for enqueueing
      |      # in case the mailbox is full. Negative values signify infinite
      |      # timeout, which should be avoided as it bears the risk of dead-lock.
      |      mailbox-push-timeout-time = 10s
      |
      |      # For Actor with Stash: The default capacity of the stash.
      |      # If negative (or zero) then an unbounded stash is used (default)
      |      # If positive then a bounded stash is used and the capacity is set using
      |      # the property
      |      stash-capacity = -1
      |    }
      |
      |    mailbox {
      |      # Mapping between message queue semantics and mailbox configurations.
      |      # Used by akka.dispatch.RequiresMessageQueue[T] to enforce different
      |      # mailbox types on actors.
      |      # If your Actor implements RequiresMessageQueue[T], then when you create
      |      # an instance of that actor its mailbox type will be decided by looking
      |      # up a mailbox configuration via T in this mapping
      |      requirements {
      |        "akka.dispatch.UnboundedMessageQueueSemantics" =
      |          akka.actor.mailbox.unbounded-queue-based
      |        "akka.dispatch.BoundedMessageQueueSemantics" =
      |          akka.actor.mailbox.bounded-queue-based
      |        "akka.dispatch.DequeBasedMessageQueueSemantics" =
      |          akka.actor.mailbox.unbounded-deque-based
      |        "akka.dispatch.UnboundedDequeBasedMessageQueueSemantics" =
      |          akka.actor.mailbox.unbounded-deque-based
      |        "akka.dispatch.BoundedDequeBasedMessageQueueSemantics" =
      |          akka.actor.mailbox.bounded-deque-based
      |        "akka.dispatch.MultipleConsumerSemantics" =
      |          akka.actor.mailbox.unbounded-queue-based
      |        "akka.dispatch.ControlAwareMessageQueueSemantics" =
      |          akka.actor.mailbox.unbounded-control-aware-queue-based
      |        "akka.dispatch.UnboundedControlAwareMessageQueueSemantics" =
      |          akka.actor.mailbox.unbounded-control-aware-queue-based
      |        "akka.dispatch.BoundedControlAwareMessageQueueSemantics" =
      |          akka.actor.mailbox.bounded-control-aware-queue-based
      |        "akka.event.LoggerMessageQueueSemantics" =
      |          akka.actor.mailbox.logger-queue
      |      }
      |
      |      unbounded-queue-based {
      |        # FQCN of the MailboxType, The Class of the FQCN must have a public
      |        # constructor with (akka.actor.ActorSystem.Settings,
      |        # com.typesafe.config.Config) parameters.
      |        mailbox-type = "akka.dispatch.UnboundedMailbox"
      |      }
      |
      |      bounded-queue-based {
      |        # FQCN of the MailboxType, The Class of the FQCN must have a public
      |        # constructor with (akka.actor.ActorSystem.Settings,
      |        # com.typesafe.config.Config) parameters.
      |        mailbox-type = "akka.dispatch.BoundedMailbox"
      |      }
      |
      |      unbounded-deque-based {
      |        # FQCN of the MailboxType, The Class of the FQCN must have a public
      |        # constructor with (akka.actor.ActorSystem.Settings,
      |        # com.typesafe.config.Config) parameters.
      |        mailbox-type = "akka.dispatch.UnboundedDequeBasedMailbox"
      |      }
      |
      |      bounded-deque-based {
      |        # FQCN of the MailboxType, The Class of the FQCN must have a public
      |        # constructor with (akka.actor.ActorSystem.Settings,
      |        # com.typesafe.config.Config) parameters.
      |        mailbox-type = "akka.dispatch.BoundedDequeBasedMailbox"
      |      }
      |
      |      unbounded-control-aware-queue-based {
      |        # FQCN of the MailboxType, The Class of the FQCN must have a public
      |        # constructor with (akka.actor.ActorSystem.Settings,
      |        # com.typesafe.config.Config) parameters.
      |        mailbox-type = "akka.dispatch.UnboundedControlAwareMailbox"
      |      }
      |
      |      bounded-control-aware-queue-based {
      |        # FQCN of the MailboxType, The Class of the FQCN must have a public
      |        # constructor with (akka.actor.ActorSystem.Settings,
      |        # com.typesafe.config.Config) parameters.
      |        mailbox-type = "akka.dispatch.BoundedControlAwareMailbox"
      |      }
      |
      |      # The LoggerMailbox will drain all messages in the mailbox
      |      # when the system is shutdown and deliver them to the StandardOutLogger.
      |      # Do not change this unless you know what you are doing.
      |      logger-queue {
      |        mailbox-type = "akka.event.LoggerMailboxType"
      |      }
      |    }
      |
      |    debug {
      |      # enable function of Actor.loggable(), which is to log any received message
      |      # at DEBUG level, see the “Testing Actor Systems” section of the Akka
      |      # Documentation at http://akka.io/docs
      |      receive = off
      |
      |      # enable DEBUG logging of all AutoReceiveMessages (Kill, PoisonPill et.c.)
      |      autoreceive = off
      |
      |      # enable DEBUG logging of actor lifecycle changes
      |      lifecycle = off
      |
      |      # enable DEBUG logging of all LoggingFSMs for events, transitions and timers
      |      fsm = off
      |
      |      # enable DEBUG logging of subscription changes on the eventStream
      |      event-stream = off
      |
      |      # enable DEBUG logging of unhandled messages
      |      unhandled = off
      |
      |      # enable WARN logging of misconfigured routers
      |      router-misconfiguration = off
      |    }
      |
      |    # Entries for pluggable serializers and their bindings.
      |    serializers {
      |      java = "akka.serialization.JavaSerializer"
      |      bytes = "akka.serialization.ByteArraySerializer"
      |    }
      |
      |    # Class to Serializer binding. You only need to specify the name of an
      |    # interface or abstract base class of the messages. In case of ambiguity it
      |    # is using the most specific configured class, or giving a warning and
      |    # choosing the “first” one.
      |    #
      |    # To disable one of the default serializers, assign its class to "none", like
      |    # "java.io.Serializable" = none
      |    serialization-bindings {
      |      "[B" = bytes
      |      "java.io.Serializable" = java
      |    }
      |
      |    # Log warnings when the default Java serialization is used to serialize messages.
      |    # The default serializer uses Java serialization which is not very performant and should not
      |    # be used in production environments unless you don't care about performance. In that case
      |    # you can turn this off.
      |    warn-about-java-serializer-usage = on
      |
      |    # To be used with the above warn-about-java-serializer-usage
      |    # When warn-about-java-serializer-usage = on, and this warn-on-no-serialization-verification = off,
      |    # warnings are suppressed for classes extending NoSerializationVerificationNeeded
      |    # to reduce noize.
      |    warn-on-no-serialization-verification = on
      |
      |    # Configuration namespace of serialization identifiers.
      |    # Each serializer implementation must have an entry in the following format:
      |    # `akka.actor.serialization-identifiers."FQCN" = ID`
      |    # where `FQCN` is fully qualified class name of the serializer implementation
      |    # and `ID` is globally unique serializer identifier number.
      |    # Identifier values from 0 to 16 are reserved for Akka internal usage.
      |    serialization-identifiers {
      |      "akka.serialization.JavaSerializer" = 1
      |      "akka.serialization.ByteArraySerializer" = 4
      |    }
      |
      |    # Configuration items which are used by the akka.actor.ActorDSL._ methods
      |    dsl {
      |      # Maximum queue size of the actor created by newInbox(); this protects
      |      # against faulty programs which use select() and consistently miss messages
      |      inbox-size = 1000
      |
      |      # Default timeout to assume for operations like Inbox.receive et al
      |      default-timeout = 5s
      |    }
      |  }
      |
      |  # Used to set the behavior of the scheduler.
      |  # Changing the default values may change the system behavior drastically so make
      |  # sure you know what you're doing! See the Scheduler section of the Akka
      |  # Documentation for more details.
      |  scheduler {
      |    # The LightArrayRevolverScheduler is used as the default scheduler in the
      |    # system. It does not execute the scheduled tasks on exact time, but on every
      |    # tick, it will run everything that is (over)due. You can increase or decrease
      |    # the accuracy of the execution timing by specifying smaller or larger tick
      |    # duration. If you are scheduling a lot of tasks you should consider increasing
      |    # the ticks per wheel.
      |    # Note that it might take up to 1 tick to stop the Timer, so setting the
      |    # tick-duration to a high value will make shutting down the actor system
      |    # take longer.
      |    tick-duration = 10ms
      |
      |    # The timer uses a circular wheel of buckets to store the timer tasks.
      |    # This should be set such that the majority of scheduled timeouts (for high
      |    # scheduling frequency) will be shorter than one rotation of the wheel
      |    # (ticks-per-wheel * ticks-duration)
      |    # THIS MUST BE A POWER OF TWO!
      |    ticks-per-wheel = 512
      |
      |    # This setting selects the timer implementation which shall be loaded at
      |    # system start-up.
      |    # The class given here must implement the akka.actor.Scheduler interface
      |    # and offer a public constructor which takes three arguments:
      |    #  1) com.typesafe.config.Config
      |    #  2) akka.event.LoggingAdapter
      |    #  3) java.util.concurrent.ThreadFactory
      |    implementation = akka.actor.LightArrayRevolverScheduler
      |
      |    # When shutting down the scheduler, there will typically be a thread which
      |    # needs to be stopped, and this timeout determines how long to wait for
      |    # that to happen. In case of timeout the shutdown of the actor system will
      |    # proceed without running possibly still enqueued tasks.
      |    shutdown-timeout = 5s
      |  }
      |
      |  io {
      |
      |    # By default the select loops run on dedicated threads, hence using a
      |    # PinnedDispatcher
      |    pinned-dispatcher {
      |      type = "PinnedDispatcher"
      |      executor = "thread-pool-executor"
      |      thread-pool-executor.allow-core-timeout = off
      |    }
      |
      |    tcp {
      |
      |      # The number of selectors to stripe the served channels over; each of
      |      # these will use one select loop on the selector-dispatcher.
      |      nr-of-selectors = 1
      |
      |      # Maximum number of open channels supported by this TCP module; there is
      |      # no intrinsic general limit, this setting is meant to enable DoS
      |      # protection by limiting the number of concurrently connected clients.
      |      # Also note that this is a "soft" limit; in certain cases the implementation
      |      # will accept a few connections more or a few less than the number configured
      |      # here. Must be an integer > 0 or "unlimited".
      |      max-channels = 256000
      |
      |      # When trying to assign a new connection to a selector and the chosen
      |      # selector is at full capacity, retry selector choosing and assignment
      |      # this many times before giving up
      |      selector-association-retries = 10
      |
      |      # The maximum number of connection that are accepted in one go,
      |      # higher numbers decrease latency, lower numbers increase fairness on
      |      # the worker-dispatcher
      |      batch-accept-limit = 10
      |
      |      # The number of bytes per direct buffer in the pool used to read or write
      |      # network data from the kernel.
      |      direct-buffer-size = 128 KiB
      |
      |      # The maximal number of direct buffers kept in the direct buffer pool for
      |      # reuse.
      |      direct-buffer-pool-limit = 1000
      |
      |      # The duration a connection actor waits for a `Register` message from
      |      # its commander before aborting the connection.
      |      register-timeout = 5s
      |
      |      # The maximum number of bytes delivered by a `Received` message. Before
      |      # more data is read from the network the connection actor will try to
      |      # do other work.
      |      # The purpose of this setting is to impose a smaller limit than the
      |      # configured receive buffer size. When using value 'unlimited' it will
      |      # try to read all from the receive buffer.
      |      max-received-message-size = unlimited
      |
      |      # Enable fine grained logging of what goes on inside the implementation.
      |      # Be aware that this may log more than once per message sent to the actors
      |      # of the tcp implementation.
      |      trace-logging = off
      |
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # to be used for running the select() calls in the selectors
      |      selector-dispatcher = "akka.io.pinned-dispatcher"
      |
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # for the read/write worker actors
      |      worker-dispatcher = "akka.actor.default-dispatcher"
      |
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # for the selector management actors
      |      management-dispatcher = "akka.actor.default-dispatcher"
      |
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # on which file IO tasks are scheduled
      |      file-io-dispatcher = "akka.actor.default-dispatcher"
      |
      |      # The maximum number of bytes (or "unlimited") to transfer in one batch
      |      # when using `WriteFile` command which uses `FileChannel.transferTo` to
      |      # pipe files to a TCP socket. On some OS like Linux `FileChannel.transferTo`
      |      # may block for a long time when network IO is faster than file IO.
      |      # Decreasing the value may improve fairness while increasing may improve
      |      # throughput.
      |      file-io-transferTo-limit = 512 KiB
      |
      |      # The number of times to retry the `finishConnect` call after being notified about
      |      # OP_CONNECT. Retries are needed if the OP_CONNECT notification doesn't imply that
      |      # `finishConnect` will succeed, which is the case on Android.
      |      finish-connect-retries = 5
      |
      |      # On Windows connection aborts are not reliably detected unless an OP_READ is
      |      # registered on the selector _after_ the connection has been reset. This
      |      # workaround enables an OP_CONNECT which forces the abort to be visible on Windows.
      |      # Enabling this setting on other platforms than Windows will cause various failures
      |      # and undefined behavior.
      |      # Possible values of this key are on, off and auto where auto will enable the
      |      # workaround if Windows is detected automatically.
      |      windows-connection-abort-workaround-enabled = off
      |    }
      |
      |    udp {
      |
      |      # The number of selectors to stripe the served channels over; each of
      |      # these will use one select loop on the selector-dispatcher.
      |      nr-of-selectors = 1
      |
      |      # Maximum number of open channels supported by this UDP module Generally
      |      # UDP does not require a large number of channels, therefore it is
      |      # recommended to keep this setting low.
      |      max-channels = 4096
      |
      |      # The select loop can be used in two modes:
      |      # - setting "infinite" will select without a timeout, hogging a thread
      |      # - setting a positive timeout will do a bounded select call,
      |      #   enabling sharing of a single thread between multiple selectors
      |      #   (in this case you will have to use a different configuration for the
      |      #   selector-dispatcher, e.g. using "type=Dispatcher" with size 1)
      |      # - setting it to zero means polling, i.e. calling selectNow()
      |      select-timeout = infinite
      |
      |      # When trying to assign a new connection to a selector and the chosen
      |      # selector is at full capacity, retry selector choosing and assignment
      |      # this many times before giving up
      |      selector-association-retries = 10
      |
      |      # The maximum number of datagrams that are read in one go,
      |      # higher numbers decrease latency, lower numbers increase fairness on
      |      # the worker-dispatcher
      |      receive-throughput = 3
      |
      |      # The number of bytes per direct buffer in the pool used to read or write
      |      # network data from the kernel.
      |      direct-buffer-size = 128 KiB
      |
      |      # The maximal number of direct buffers kept in the direct buffer pool for
      |      # reuse.
      |      direct-buffer-pool-limit = 1000
      |
      |      # Enable fine grained logging of what goes on inside the implementation.
      |      # Be aware that this may log more than once per message sent to the actors
      |      # of the tcp implementation.
      |      trace-logging = off
      |
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # to be used for running the select() calls in the selectors
      |      selector-dispatcher = "akka.io.pinned-dispatcher"
      |
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # for the read/write worker actors
      |      worker-dispatcher = "akka.actor.default-dispatcher"
      |
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # for the selector management actors
      |      management-dispatcher = "akka.actor.default-dispatcher"
      |    }
      |
      |    udp-connected {
      |
      |      # The number of selectors to stripe the served channels over; each of
      |      # these will use one select loop on the selector-dispatcher.
      |      nr-of-selectors = 1
      |
      |      # Maximum number of open channels supported by this UDP module Generally
      |      # UDP does not require a large number of channels, therefore it is
      |      # recommended to keep this setting low.
      |      max-channels = 4096
      |
      |      # The select loop can be used in two modes:
      |      # - setting "infinite" will select without a timeout, hogging a thread
      |      # - setting a positive timeout will do a bounded select call,
      |      #   enabling sharing of a single thread between multiple selectors
      |      #   (in this case you will have to use a different configuration for the
      |      #   selector-dispatcher, e.g. using "type=Dispatcher" with size 1)
      |      # - setting it to zero means polling, i.e. calling selectNow()
      |      select-timeout = infinite
      |
      |      # When trying to assign a new connection to a selector and the chosen
      |      # selector is at full capacity, retry selector choosing and assignment
      |      # this many times before giving up
      |      selector-association-retries = 10
      |
      |      # The maximum number of datagrams that are read in one go,
      |      # higher numbers decrease latency, lower numbers increase fairness on
      |      # the worker-dispatcher
      |      receive-throughput = 3
      |
      |      # The number of bytes per direct buffer in the pool used to read or write
      |      # network data from the kernel.
      |      direct-buffer-size = 128 KiB
      |
      |      # The maximal number of direct buffers kept in the direct buffer pool for
      |      # reuse.
      |      direct-buffer-pool-limit = 1000
      |
      |      # Enable fine grained logging of what goes on inside the implementation.
      |      # Be aware that this may log more than once per message sent to the actors
      |      # of the tcp implementation.
      |      trace-logging = off
      |
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # to be used for running the select() calls in the selectors
      |      selector-dispatcher = "akka.io.pinned-dispatcher"
      |
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # for the read/write worker actors
      |      worker-dispatcher = "akka.actor.default-dispatcher"
      |
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # for the selector management actors
      |      management-dispatcher = "akka.actor.default-dispatcher"
      |    }
      |
      |    dns {
      |      # Fully qualified config path which holds the dispatcher configuration
      |      # for the manager and resolver router actors.
      |      # For actual router configuration see akka.actor.deployment./IO-DNS/*
      |      dispatcher = "akka.actor.default-dispatcher"
      |
      |      # Name of the subconfig at path akka.io.dns, see inet-address below
      |      resolver = "inet-address"
      |
      |      inet-address {
      |        # Must implement akka.io.DnsProvider
      |        provider-object = "akka.io.InetAddressDnsProvider"
      |
      |        # These TTLs are set to default java 6 values
      |        positive-ttl = 30s
      |        negative-ttl = 10s
      |
      |        # How often to sweep out expired cache entries.
      |        # Note that this interval has nothing to do with TTLs
      |        cache-cleanup-interval = 120s
      |      }
      |    }
      |  }
      |
      |
      |}
    """.stripMargin
}
