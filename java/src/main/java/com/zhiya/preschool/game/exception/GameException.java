package com.zhiya.preschool.game.exception;

/** 可向小程序返回的业务错误，携带 HTTP 状态码和提示文案。 */
public class GameException extends RuntimeException {
    private final int status;

    public GameException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
