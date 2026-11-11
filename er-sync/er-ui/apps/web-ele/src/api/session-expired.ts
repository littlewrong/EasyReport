/** Authentication rejection already handled by the session recovery flow. */
export class SessionExpiredError extends Error {
  readonly code = 401;

  constructor() {
    super('登录状态已过期，请重新登录');
    this.name = 'SessionExpiredError';
  }
}
