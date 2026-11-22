package edu.scholarchain.services

/** Entry point for the ScholarChain REST service. */
@main def scholarChainServer(): Unit =
  val config                   = ServiceConfig.fromEnv()
  val (workflow, graph, audit) = RuntimeComposer.composeStubs()
  val routes                   = Routes(workflow, graph, audit, config)
  val server                   = routes.start()
  println(s"ScholarChain server started on ${config.host}:${config.port}")
  Runtime.getRuntime.addShutdownHook(Thread(() => server.stop(5)))
