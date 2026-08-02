"""
models.py -- SQLAlchemy ORM models for the router backend.

An ORM (Object-Relational Mapper) lets you work with Postgres tables as
Python classes instead of writing raw SQL for every insert/query. Each
class below maps to one table; each class attribute maps to one column.

Two tables:
  - Device        one row per unique device seen on the network (by MAC)
  - NetworkFlow   one row per traffic flow observed and pulled from ntopng
                  (a "flow" is one conversation between two IPs/ports over
                  some time window -- not literally every packet)
"""

from datetime import datetime, timezone

from sqlalchemy import (
    BigInteger,
    Column,
    DateTime,
    ForeignKey,
    Integer,
    String,
)
from sqlalchemy.orm import DeclarativeBase, relationship


def utc_now() -> datetime:
    """Timezone-aware 'now' in UTC. datetime.utcnow() is deprecated as of
    Python 3.12 -- this is the replacement pattern."""
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    """Every model class below inherits from this. SQLAlchemy uses it to
    keep track of all your table definitions in one place."""
    pass


class Device(Base):
    """One row per unique device seen on the LAN, identified by MAC address.

    We keep this separate from NetworkFlow so we're not repeating the
    hostname/vendor guess on every single flow row -- standard normalization:
    device info lives once, flows reference it by foreign key.
    """
    __tablename__ = "devices"

    id = Column(Integer, primary_key=True)
    mac_address = Column(String(17), unique=True, nullable=False, index=True)
    hostname = Column(String(255), nullable=True)
    first_seen = Column(DateTime(timezone=True), default=utc_now, nullable=False)
    last_seen = Column(DateTime(timezone=True), default=utc_now, nullable=False)

    # This lets you do `some_device.flows` in Python and get back all
    # NetworkFlow rows for that device, without writing a JOIN by hand.
    flows = relationship("NetworkFlow", back_populates="device")

    def __repr__(self) -> str:
        return f"<Device {self.mac_address} ({self.hostname or 'unknown'})>"


class NetworkFlow(Base):
    """One row per traffic flow pulled from ntopng.

    device_id is nullable because ntopng may report a flow for an IP we
    haven't matched to a known device/MAC yet (e.g. before ARP resolves).
    """
    __tablename__ = "network_flows"

    id = Column(Integer, primary_key=True)
    device_id = Column(Integer, ForeignKey("devices.id"), nullable=True, index=True)

    timestamp = Column(DateTime(timezone=True), default=utc_now, nullable=False, index=True)
    src_ip = Column(String(45), nullable=False)  # 45 chars covers IPv6
    dst_ip = Column(String(45), nullable=False)
    dst_port = Column(Integer, nullable=True)
    protocol = Column(String(10), nullable=True)  # "TCP", "UDP", etc.

    bytes_sent = Column(BigInteger, default=0, nullable=False)
    bytes_recv = Column(BigInteger, default=0, nullable=False)

    device = relationship("Device", back_populates="flows")

    def __repr__(self) -> str:
        return f"<NetworkFlow {self.src_ip} -> {self.dst_ip}:{self.dst_port} ({self.protocol})>"
