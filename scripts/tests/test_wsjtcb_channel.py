"""Reject altered desktop channel sources before compiling reference fixtures."""
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import wsjtcb_channel as reference


class ChannelProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='channel-provenance-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        shutil.copytree(reference.REF / 'source/channel', self.root / 'source/channel')
        self.manifest = self.root / 'channel-sources.json'
        self.manifest.write_bytes(reference.MANIFEST.read_bytes())
        for name, value in [('REF', self.root), ('MANIFEST', self.manifest)]:
            mock = patch.object(reference, name, value)
            mock.start()
            self.addCleanup(mock.stop)

    def test_modified_crc_source_fails(self):
        path = self.root / 'source/channel/lib/crc14.cpp'
        path.write_bytes(path.read_bytes() + b'\n// modified\n')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            reference.load_sources()

    def test_mixed_reference_revision_fails(self):
        manifest = json.loads(self.manifest.read_text())
        manifest['revision'] = '0' * 40
        self.manifest.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'revision mismatch'):
            reference.load_sources()
