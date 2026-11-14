from fastapi import APIRouter

from app.api.v1 import auth, core, datasources, internal, menus, reports, roles, users

api_router = APIRouter()
api_router.include_router(auth.router)
api_router.include_router(core.router)
api_router.include_router(menus.router)
api_router.include_router(roles.router)
api_router.include_router(users.router)
api_router.include_router(reports.router)
api_router.include_router(datasources.router)
api_router.include_router(internal.router)
