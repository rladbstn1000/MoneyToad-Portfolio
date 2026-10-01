"""Use checked npm dependencies while keeping all mutable tool caches in a private copy."""
import hashlib
import os
from pathlib import Path
import shutil
import uuid


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dependency_state(root):
    """Detect changed package/cache entries without publishing package paths or values."""
    digest = hashlib.sha256()
    for directory, directories, files in os.walk(root, followlinks=False):
        directories.sort()
        for name in sorted(directories + files):
            path = Path(directory) / name
            item = path.lstat()
            row = (str(path.relative_to(root)), item.st_mode, item.st_size, item.st_mtime_ns,
                   os.readlink(path) if path.is_symlink() else '')
            digest.update(repr(row).encode())
    return digest.hexdigest()


class DependencyOverlay:
    """Call create before launching tools, close only after all child tools exit.

    Tools get a real node_modules directory in the private checkout. Individual
    packages and binaries are links; .tmp/.vite/.vite-temp and other caches are
    owned local directories instead of writes through a whole-directory link.
    Never run npm install/ci or package lifecycle scripts through this overlay.
    """
    def __init__(self, frontend, dependencies):
        self.frontend = Path(frontend).resolve()
        self.dependencies = Path(dependencies).resolve()
        self.source = self.dependencies / 'node_modules'
        self.target = self.frontend / 'node_modules'
        self.marker = self.target / '.moneytoad-owned-overlay'
        self.owner = uuid.uuid4().hex
        self.before = None
        self.created = False
        self.report = {'dependency_manifest_match': False, 'dependencies_preserved': False,
                       'overlay_removed': False}

    def create(self):
        if self.target.exists() or self.target.is_symlink() or not self.source.is_dir():
            raise ValueError('EXPLICIT_DEPENDENCIES_OR_EMPTY_TARGET_REQUIRED')
        for name in ('package.json', 'package-lock.json'):
            if checksum(self.frontend / name) != checksum(self.dependencies / name):
                raise ValueError('DEPENDENCY_MANIFEST_MISMATCH')
        self.report['dependency_manifest_match'] = True
        self.before = dependency_state(self.source)
        self.target.mkdir(mode=0o700)
        self.created = True
        self.marker.write_text(self.owner)
        try:
            for entry in self.source.iterdir():
                if entry.name.startswith('.') and entry.name != '.bin':
                    continue
                destination = self.target / entry.name
                if entry.name == '.bin' or entry.name.startswith('@'):
                    destination.mkdir()
                    for package in entry.iterdir():
                        resolved = package.resolve()
                        if not resolved.is_relative_to(self.source.resolve()):
                            raise ValueError('DEPENDENCY_LINK_OUTSIDE_INSTALLATION')
                        (destination / package.name).symlink_to(resolved, target_is_directory=resolved.is_dir())
                else:
                    resolved = entry.resolve()
                    if not resolved.is_relative_to(self.source.resolve()):
                        raise ValueError('DEPENDENCY_LINK_OUTSIDE_INSTALLATION')
                    destination.symlink_to(resolved, target_is_directory=resolved.is_dir())
        except BaseException:
            self.close()
            raise
        return self

    def close(self):
        if self.created:
            if self.target.is_symlink() or not self.marker.is_file() or self.marker.read_text() != self.owner:
                raise ValueError('DEPENDENCY_OVERLAY_OWNERSHIP_UNCONFIRMED')
            shutil.rmtree(self.target)
            self.created = False
        self.report['overlay_removed'] = not self.target.exists() and not self.target.is_symlink()
        self.report['dependencies_preserved'] = self.before is not None and self.before == dependency_state(self.source)
        if not all(self.report.values()):
            raise ValueError('DEPENDENCY_OVERLAY_PRESERVATION_FAILED')
