from fastapi import APIRouter

from app.api.v1 import auth, bi_datasources, bi_reports, core, internal, menus, roles, users

api_router = APIRouter()
api_router.include_router(auth.router)
api_router.include_router(core.router)
api_router.include_router(menus.router)
api_router.include_router(roles.router)
api_router.include_router(users.router)
api_router.include_router(bi_reports.router)
api_router.include_router(bi_datasources.router)
api_router.include_router(internal.router)
