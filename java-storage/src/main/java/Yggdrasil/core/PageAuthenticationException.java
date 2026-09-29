package Yggdrasil.core;

import java.io.IOException;

public class PageAuthenticationException extends IOException {

    public PageAuthenticationException(String message) {
        super(message);
    }

    public PageAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
