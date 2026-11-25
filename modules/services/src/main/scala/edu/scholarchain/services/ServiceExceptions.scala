package edu.scholarchain.services

sealed abstract class ServiceException(msg: String) extends RuntimeException(msg)

case class BadRequestException(msg: String) extends ServiceException(msg)

case class NotFoundException(msg: String) extends ServiceException(msg)

case class ConflictException(msg: String) extends ServiceException(msg)

case class UnprocessableException(msg: String) extends ServiceException(msg)

case class TimeoutException(msg: String) extends ServiceException(msg)

case class DownstreamException(msg: String) extends ServiceException(msg)

/** The service cannot take more work right now (bounded executor saturated); retryable. */
case class UnavailableException(msg: String) extends ServiceException(msg)
