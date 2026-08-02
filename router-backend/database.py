"""
database.py -- Postgres connection setup.

This is the standard SQLAlchemy pattern:
  1. `engine`       the actual connection to Postgres, built from DATABASE_URL
  2. `SessionLocal`  a factory that hands out new "sessions" (a session is
                     basically a workspace for a set of queries/inserts --
                     you open one, do your work, commit, close)
  3. `get_db()`      a small helper FastAPI uses to give each request its
                     own session and guarantee it gets closed afterward

You won't usually call SessionLocal() directly outside of scripts like
poller.py -- inside FastAPI routes, use the get_db() dependency instead
(see main.py for how that's wired in).
"""

import os

from dotenv import load_dotenv
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

load_dotenv()  # reads .env into environment variables, if present

DATABASE_URL = os.getenv("DATABASE_URL")
if not DATABASE_URL:
    raise RuntimeError(
        "DATABASE_URL is not set. Copy .env.example to .env and fill in "
        "your Postgres connection string."
    )

engine = create_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def get_db():
    """FastAPI dependency: yields one DB session per request, closes it
    afterward even if the request raised an error."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
