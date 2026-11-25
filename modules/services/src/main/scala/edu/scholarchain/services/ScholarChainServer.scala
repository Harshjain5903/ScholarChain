package edu.scholarchain.services

/**
 * Entry point for the ScholarChain REST service. `SCHOLARCHAIN_RUNTIME_MODE=stub|aws` selects the
 * runtime (default stub); see [[RuntimeComposer.fromEnv]].
 */
@main def scholarChainServer(): Unit =
  val config = ServiceConfig.fromEnv()
  RuntimeComposer.fromEnv(sys.env, config) match
    case Left(err) =>
      System.err.println(s"ScholarChain server not started: $err")
      sys.exit(2)
    case Right(rt) =>
      val routes = Routes(
        rt.workflow,
        rt.graph,
        rt.audit,
        config,
        results = rt.results,
        readiness = rt.readiness
      )
      val server = routes.start()
      val notReady =
        if rt.readiness.ready then ""
        else s" (not ready; missing: ${rt.readiness.missing.mkString(", ")})"
      println(
        s"ScholarChain server started on ${config.host}:${config.port} " +
          s"mode=${rt.mode.wireName}$notReady"
      )
      Runtime.getRuntime.addShutdownHook(Thread(() => server.stop(5)))
