"""Small local progress database. No media bytes or temporary URL tokens are stored."""
from __future__ import annotations
import ctypes
from contextlib import contextmanager
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time


def volume_keys(root: Path, explicit: bool = False) -> tuple[str, str]:
    st = root.stat()
    if explicit or os.name != 'nt':
        # Test roots retain identity when their folder/mount letter is renamed.
        key = f'root:{st.st_dev}:{st.st_ino}'
        return key, key
    kernel = ctypes.windll.kernel32
    label = ctypes.create_unicode_buffer(261)
    serial = wintypes.DWORD()
    fs = ctypes.create_unicode_buffer(64)
    if not kernel.GetVolumeInformationW(str(root), label, len(label), ctypes.byref(serial), None, None, fs, len(fs)):
        raise OSError('Cannot identify volume safely')
    guid = ctypes.create_unicode_buffer(261)
    ok = kernel.GetVolumeNameForVolumeMountPointW(str(root), guid, len(guid))
    fallback = f'serial:{serial.value:08x}:{fs.value.casefold()}:root:{st.st_ino}'
    return (f'guid:{guid.value.casefold()}:{serial.value:08x}' if ok else fallback), fallback


class ProgressStore:
    def __init__(self, path: Path):
        self.path = str(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('''CREATE TABLE IF NOT EXISTS progress (
                file_key TEXT PRIMARY KEY, volume_id TEXT NOT NULL, volume_fallback TEXT NOT NULL,
                relative_path TEXT NOT NULL, file_size INTEGER NOT NULL, modified_ns INTEGER NOT NULL, file_inode TEXT NOT NULL,
                position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
                last_watched_ms INTEGER NOT NULL, completed INTEGER NOT NULL)''')
            db.execute('CREATE INDEX IF NOT EXISTS progress_fallback ON progress(volume_fallback, relative_path, file_size, modified_ns)')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=3)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA synchronous=FULL')
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def identity(volume: str, fallback: str, relative: str, size: int, modified_ns: int, inode: str = '') -> dict:
        relative = relative.replace('\\', '/').casefold()
        values = [volume, relative, size, modified_ns, inode]
        return dict(file_key=hashlib.sha256(json.dumps(values, ensure_ascii=False).encode()).hexdigest(),
                    volume_id=volume, volume_fallback=fallback, relative_path=relative,
                    file_size=size, modified_ns=modified_ns, file_inode=inode)

    def _find(self, db, identity):
        row = db.execute('SELECT * FROM progress WHERE file_key=?', (identity['file_key'],)).fetchone()
        if row is None:
            rows = db.execute('''SELECT * FROM progress WHERE volume_fallback=? AND relative_path=?
                AND file_size=? AND modified_ns=? AND file_inode=?''', tuple(identity[k] for k in ('volume_fallback','relative_path','file_size','modified_ns','file_inode'))).fetchall()
            # An ambiguous serial/clone match must not resume the wrong video.
            if len(rows) == 1: row = rows[0]
        return dict(row) if row is not None else None

    def get(self, identity):
        with self.connect() as db:
            row = self._find(db, identity)
        if row is None: return None
        return {k: (bool(row[k]) if k == 'completed' else row[k]) for k in
                ('position_ms', 'duration_ms', 'last_watched_ms', 'completed')}

    def put(self, identity, payload):
        for key in ('position_ms', 'duration_ms'):
            if type(payload.get(key)) is not int or not 0 <= payload[key] <= 10**12:
                raise ValueError(f'Invalid {key}')
        if type(payload.get('completed', False)) is not bool or type(payload.get('reset', False)) is not bool:
            raise ValueError('Invalid progress flags')
        position, duration = payload['position_ms'], payload['duration_ms']
        completed, reset = payload.get('completed', False), payload.get('reset', False)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            old = self._find(db, identity)
            if old and position == 0 and not reset and not completed:
                return self.get(identity)  # A transient zero never erases valid progress.
            if not duration and old: duration = old['duration_ms']
            if duration: position = min(position, duration)
            if completed and duration: position = duration
            if reset: position, completed = 0, False
            if old and old['file_key'] != identity['file_key']:
                db.execute('DELETE FROM progress WHERE file_key=?', (old['file_key'],))
            db.execute('''INSERT OR REPLACE INTO progress VALUES (?,?,?,?,?,?,?,?,?,?,?)''',
                       tuple(identity[k] for k in ('file_key','volume_id','volume_fallback','relative_path','file_size','modified_ns','file_inode')) +
                       (position, duration, int(time.time()*1000), int(completed)))
        return self.get(identity)

    def delete(self, identity):
        with self.connect() as db:
            old = self._find(db, identity)
            if old: db.execute('DELETE FROM progress WHERE file_key=?', (old['file_key'],))
