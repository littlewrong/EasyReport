from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.response import ok
from app.modules.bi import dashboard_service
from app.modules.bi.schemas import (
    ProjectCreate,
    ProjectEdit,
    ProjectPublish,
    ProjectSave,
)
from app.modules.system.models import SysUser

router = APIRouter(
    prefix="/bi/project",
    tags=["bi-project"],
    dependencies=[Depends(get_current_user)],
)


@router.post("/create")
def create_project(
    body: ProjectCreate,
    db: Session = Depends(get_db),
    user: SysUser = Depends(get_current_user),
):
    return ok(dashboard_service.create_project(db, user, body))


@router.get("/list")
def list_projects(
    page: int = 1,
    limit: int = 12,
    db: Session = Depends(get_db),
    user: SysUser = Depends(get_current_user),
):
    return ok(dashboard_service.list_projects(db, user, page, limit))


@router.get("/getData")
def get_project_data(projectId: int, db: Session = Depends(get_db)):
    return ok(dashboard_service.get_project_data(db, projectId))


@router.post("/save/data")
def save_project_data(body: ProjectSave, db: Session = Depends(get_db)):
    return ok(dashboard_service.save_project_data(db, body))


@router.post("/edit")
def edit_project(body: ProjectEdit, db: Session = Depends(get_db)):
    return ok(dashboard_service.edit_project(db, body))


@router.post("/publish")
def publish_project(body: ProjectPublish, db: Session = Depends(get_db)):
    return ok(dashboard_service.publish_project(db, body.id, body.state))


@router.delete("/{project_id}")
def delete_project(project_id: int, db: Session = Depends(get_db)):
    return ok(dashboard_service.delete_project(db, project_id))
