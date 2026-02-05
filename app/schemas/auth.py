from pydantic import BaseModel, EmailStr

class RequestLinkIn(BaseModel):
    email: EmailStr

class RequestLinkOut(BaseModel):
    sent: bool = True
    token: str | None = None
    link: str | None = None

class RedeemIn(BaseModel):
    token: str

class RedeemOut(BaseModel):
    access_token: str
    token_type: str = "bearer"

class AccessTokenOut(RedeemOut):
    pass
