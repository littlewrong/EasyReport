package cn.easyreport.exception;

/**
 * A report file error whose message is safe to return to the client.
 * Filesystem exceptions must be logged separately, not attached as a cause.
 */
public class ReportFileException extends ReportException {

	private static final long serialVersionUID = 1L;
	private final int statusCode;

	public ReportFileException(int statusCode, String message) {
		super(message);
		this.statusCode = statusCode;
	}

	public int getStatusCode() {
		return statusCode;
	}
}
