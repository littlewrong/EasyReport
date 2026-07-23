from pydantic import BaseModel


class LoginParams(BaseModel):
    username: str
    password: str
