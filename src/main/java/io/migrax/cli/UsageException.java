package io.migrax.cli;

/** An expected user error: printed as one line plus a hint, without a stack trace. */
final class UsageException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  final String hint;
  final ErrorCode code;

  UsageException(String message, String hint) {
    this(ErrorCode.USAGE, message, hint);
  }

  UsageException(ErrorCode code, String message, String hint) {
    super(message);
    this.code = code;
    this.hint = hint;
  }
}
