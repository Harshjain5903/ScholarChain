package edu.scholarchain.services

/** Maps service exceptions to HTTP status codes and error response payloads. */
object ErrorHandler:

  def toErrorResponse(
    e: Throwable,
    traceId: String
  ): (Int, ErrorResponse) =
    e match
      case e: BadRequestException =>
        (400, ErrorResponse("BAD_REQUEST", e.getMessage, traceId, false))
      case e: NotFoundException =>
        (404, ErrorResponse("NOT_FOUND", e.getMessage, traceId, false))
      case e: ConflictException =>
        (409, ErrorResponse("CONFLICT", e.getMessage, traceId, false))
      case e: UnprocessableException =>
        (
          422,
          ErrorResponse("UNPROCESSABLE", e.getMessage, traceId, false)
        )
      case e: TimeoutException =>
        (504, ErrorResponse("TIMEOUT", e.getMessage, traceId, true))
      case e: DownstreamException =>
        (
          502,
          ErrorResponse("DOWNSTREAM_ERROR", e.getMessage, traceId, true)
        )
      case _ =>
        (
          500,
          ErrorResponse(
            "INTERNAL_ERROR",
            "An unexpected error occurred",
            traceId,
            false
          )
        )
