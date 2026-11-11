export class AdminRequestError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export async function adminRequest<T>(path: string, body?: object): Promise<T> {
  const response = await fetch(
    "/api/admin" + path,
    body === undefined
      ? {}
      : {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Admin-Request": "1",
          },
          body: JSON.stringify(body),
        },
  );
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    throw new AdminRequestError(
      response.status,
      data?.message ||
        (response.status === 404
          ? "请确认 Java 已用 local 配置启动"
          : "请求失败，请检查表单或后台服务"),
    );
  }
  return data as T;
}
