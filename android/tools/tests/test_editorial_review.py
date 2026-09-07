import copy
import importlib.util
import json
import tempfile
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

if __name__ == '__main__': unittest.main()
