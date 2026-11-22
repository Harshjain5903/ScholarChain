Use the service-engineer agent to implement ScholarChain RESTful microservices under modules/services/.

Create 8 files: ServiceConfig.scala, JsonCodecs.scala, ServiceExceptions.scala, ErrorHandler.scala, WorkflowPort.scala, Routes.scala, RuntimeComposer.scala, ScholarChainServer.scala plus ServiceRoutesSpec.scala with 18 tests.

7 endpoints on JDK HttpServer: POST /v1/query, GET /v1/jobs/{jobId}, GET /v1/evidence/{evidenceId}, GET /v1/graph/nodes/{nodeId}/neighbors, GET /v1/explain/{queryId}, GET /healthz, GET /readyz.

All JSON via uPickle. Idempotency-Key support. Trace IDs on every response. Bounded request body, graph depth 1-3, limit 1-200.
