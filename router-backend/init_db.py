"""
init_db.py -- creates the tables defined in models.py.

Run this once, after your Postgres database exists and DATABASE_URL in
.env points to it:

    python init_db.py

This is the simple/beginner approach (Base.metadata.create_all). It's
fine for a project like this. For anything with a real production
lifecycle -- schema changes over time, multiple environments -- you'd
eventually want Alembic (SQLAlchemy's migration tool) instead, since
create_all only creates tables that don't exist yet; it won't alter an
existing table if you change a model later.
"""

from database import engine
from models import Base

if __name__ == "__main__":
    print("Creating tables...")
    Base.metadata.create_all(bind=engine)
    print("Done. Tables created: devices, network_flows")
