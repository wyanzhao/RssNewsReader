import copy
import importlib.util
import json
import tempfile
import subprocess
import sys
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('editorial_review', Path(__file__).parents[1] / 'editorial_review.py')
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)

class PacketTests(unittest.TestCase):
    def fixture(self, path, feedback=None):
        a = {'link': 'https://example.test/a', 'title': 'Research result', 'source': 'Lab', 'article_text': 'Measured result'}
        contents = {'raw.json': {'articles': [a]}, 'part1_plan.json': {'items': [{'link': a['link'], 'summary_zh': '研究结果'}]},
                    'part1_shortlist.json': {'links': [a['link']], 'excluded': []},
                    'part1_brief.json': {'articles': [a], 'editor_feedback': feedback or []},
                    'part1_shortlist_context.json': {'meta': {'run_id': str(path)}, 'articles': [a]}, 'run_config.json': {'topN': 30}}
        with zipfile.ZipFile(path, 'w') as archive:
            for name, value in contents.items(): archive.writestr(name, json.dumps(value))
    def test_development_assessment_is_visible_as_model_claim_with_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'run.zip'; self.fixture(p); run=review.load_run(p)
            run['plan']['items'][0]['development'] = {'baseline_date':'2026-01-02', 'change_zh':'新增实验结果',
                'evidence_link':'https://example.test/a', 'evidence_quote':'Measured result'}
            text=review.render_arm(run, 'A')
            self.assertIn('Model-claimed development since 2026-01-02: 新增实验结果', text)
            self.assertIn('Evidence source: https://example.test/a', text)
            self.assertIn('Source excerpt (not independent verification): Measured result', text)

    def test_pair_hides_paths_and_per_arm_feedback(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); a = root/'baseline.zip'; b = root/'candidate.zip'
            self.fixture(a); self.fixture(b, ['Prefer compiler research'])
            result = review.prepare([a,b], root/'packet')
            self.assertEqual(2,result['arms'])
            text = (root/'packet/source-evidence.json').read_text()
            self.assertNotIn(str(a),text); self.assertNotIn(str(b),text)
            data=json.loads(text)
            self.assertEqual(['Prefer compiler research'], data['review_preferences'])
            self.assertTrue(all('feedback' not in arm for arm in data['arms'].values()))
    def test_changed_sources_and_cache_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'run.zip'; self.fixture(p); left=review.load_run(p)
            right=copy.deepcopy(left); right['pool_hash']='different'
            with self.assertRaises(ValueError): review.comparison_controls(left,right)
            right=copy.deepcopy(left); right['context']['articles'][0]['cached_summary_zh']='changed'
            with self.assertRaises(ValueError): review.comparison_controls(left,right)
            right=copy.deepcopy(left); right['config']['topN']=20
            with self.assertRaises(ValueError): review.comparison_controls(left,right)
    def test_duplicate_source_references_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'run.zip'; self.fixture(p)
            with zipfile.ZipFile(p) as z: data={n:json.loads(z.read(n)) for n in z.namelist()}
            data['part1_shortlist.json']['links'] *= 2
            with zipfile.ZipFile(p,'w') as z:
                for n,v in data.items(): z.writestr(n,json.dumps(v))
            with self.assertRaises(ValueError): review.load_run(p)

    def test_final_exclusions_and_merged_sources_are_reviewable(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'run.zip'; self.fixture(p)
            with zipfile.ZipFile(p) as z: data={n:json.loads(z.read(n)) for n in z.namelist()}
            link=data['raw.json']['articles'][0]['link']
            data['part1_plan.json']['excluded']=[{'link':link,'reason':'duplicate selection'}]
            with zipfile.ZipFile(p,'w') as z:
                for n,v in data.items(): z.writestr(n,json.dumps(v))
            with self.assertRaisesRegex(ValueError,'exactly once'): review.load_run(p)
            run={'pool':[{'link':link,'title':'Primary','source':'Lab'},
                {'link':link+'2','title':'Supporting article','source':'Lab'}],
                'plan':{'items':[{'link':link,'summary_zh':'摘要','also_links':[link+'2']}]}}
            self.assertIn('Merged source: Supporting article',review.render_arm(run,'A'))

    def test_nested_experiment_requires_complete_and_original_pool(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=root/'source.zip'; self.fixture(source)
            with zipfile.ZipFile(source) as z:
                data={n:json.loads(z.read(n)) for n in z.namelist()}
            def write(status, drift=False):
                with zipfile.ZipFile(source, 'w') as z:
                    for n,v in data.items(): z.writestr(n,json.dumps(v))
                    z.writestr('comparisons/trial/manifest.json',json.dumps({'status':status}))
                    for arm in ('baseline','candidate'):
                        for n,v in data.items():
                            v=copy.deepcopy(v)
                            if n=='part1_brief.json' and arm=='candidate': v['editor_feedback']=['Prefer chips']
                            if drift and n=='raw.json' and arm=='candidate': v['articles'][0]['title']='Changed source'
                            z.writestr(f'comparisons/trial/{arm}/{n}',json.dumps(v))
            def invoke(name):
                return subprocess.run([sys.executable,str(Path(review.__file__)), '--run',str(source),
                    '--experiment','trial','--output',str(root/name)],capture_output=True,text=True)
            write('running'); self.assertNotEqual(0,invoke('partial').returncode)
            self.assertFalse((root/'partial').exists())
            write('complete',True); self.assertNotEqual(0,invoke('drift').returncode)
            write('complete'); result=invoke('complete')
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertTrue((root/'complete/runtime-provenance.json').exists())

if __name__ == '__main__': unittest.main()
