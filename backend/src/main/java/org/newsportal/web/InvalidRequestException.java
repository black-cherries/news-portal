package org.newsportal.web;

/** A client error whose message is safe to return in the response. */
public class InvalidRequestException extends RuntimeException {

	public InvalidRequestException(String message) {
		super(message);
	}

}
