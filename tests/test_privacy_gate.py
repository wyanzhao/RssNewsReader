import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]/'scripts'
sys.path.insert(0, str(SCRIPTS))
import privacy_gate as gate

class PrivacyGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Fixture')
        self.git('config', 'user.email', '123+fixture@users.noreply.github.com')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], stderr=subprocess.DEVNULL).decode().strip()

    def commit(self, name, text):
        (self.repo/name).write_text(text)
        self.git('add', name)
        self.git('commit','-qm','fixture')
        return self.git('rev-parse','HEAD')

    def test_staged_bytes_not_worktree_and_redacted_output(self):
        canary = '/Us'+'ers/'+'private-person'
        self.commit('note.md', 'safe')
        (self.repo/'note.md').write_text(canary)
        self.git('add','note.md')
        (self.repo/'note.md').write_text('safe')
        rows,_=gate.collect_git(self.repo,staged=True)
        self.assertIn('personal-home',{x['rule'] for x in rows})
        self.assertNotIn(canary,json.dumps(rows))

    def test_history_finds_deleted_content_but_clean_new_range_passes(self):
        bad = 'RFC'+'12345678'
        self.commit('note.md',bad)
        base = self.commit('note.md','safe')
        self.commit('other.md','safe too')
        rows,_=gate.collect_git(self.repo,history=True)
        self.assertIn('device-serial',{x['rule'] for x in rows})
        rows,_=gate.collect_git(self.repo,base=base)
        self.assertEqual([],rows)

    def test_commit_identity_cannot_hide_behind_clean_content(self):
        self.git('config','user.email','person@'+'gmail.com')
        self.commit('note.md','safe')
        rows,_=gate.collect_git(self.repo,history=True)
        self.assertIn('commit-email-not-noreply',{x['rule'] for x in rows})

    def test_annotated_tag_metadata_is_scanned(self):
        self.commit('note.md','safe')
        self.git('tag','-a','test','-m','device '+'RFC'+'12345678')
        rows,_=gate.collect_git(self.repo,revision='test',history=True)
        self.assertIn('device-serial',{x['rule'] for x in rows})

    def test_forbidden_archive_and_symlink_content_are_not_followed(self):
        self.commit('backup.zip','fake archive')
        (self.repo/'link').symlink_to('/Us'+'ers/'+'private-person')
        self.git('add','link')
        rows,_=gate.collect_git(self.repo,staged=True)
        self.assertIn('forbidden-artifact',{x['rule'] for x in rows})
        self.assertIn('personal-home',{x['rule'] for x in rows})

    def test_missing_scanner_fails_closed(self):
        with patch.object(gate.shutil,'which',return_value=None):
            with self.assertRaises(gate.GateError):gate.run_gitleaks(self.repo)

    def test_apk_subject_and_utf16_secret_are_checked(self):
        apk=self.repo/'fixture.apk'
        canary='sk-'+'A1b2C3d4E5f6'*4
        with zipfile.ZipFile(apk,'w') as z:z.writestr('res/example.xml',canary.encode('utf-16le'))
        response=SimpleNamespace(returncode=0,stdout=b'Verified using v2 scheme: true\nSigner #1 certificate DN: CN=Personal Identity\n')
        with patch.object(gate.subprocess,'run',return_value=response):rows,_=gate.inspect_apk(apk,'apksigner')
        self.assertIn('apk-certificate-subject-not-approved',{x['rule'] for x in rows})
        self.assertIn('credential-token',{x['rule'] for x in rows})
        self.assertNotIn(canary,json.dumps(rows))

    def test_neutral_certificate_and_clean_apk_pass_custom_rules(self):
        apk=self.repo/'fixture.apk'
        with zipfile.ZipFile(apk,'w') as z:z.writestr('assets/readme.txt','safe text')
        response=SimpleNamespace(returncode=0,stdout=b'Verified using v3 scheme: true\nSigner #1 certificate DN: CN=DailyNews\n')
        with patch.object(gate.subprocess,'run',return_value=response):rows,_=gate.inspect_apk(apk,'apksigner')
        self.assertEqual([],rows)

if __name__ == '__main__':unittest.main()
