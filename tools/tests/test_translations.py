"""Check shipped Plus translations without an Android runtime."""
from pathlib import Path
from collections import Counter
import re
import unittest
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[2] / 'app/src/main/res'
LANGUAGES = ('zh-rCN', 'zh-rTW', 'zh-rHK', 'ja', 'ko', 'ru', 'de', 'fr', 'it', 'es', 'pt', 'ar', 'hi')


def strings(path):
    return {item.attrib['name']: ''.join(item.itertext()) for item in ET.parse(path).getroot() if item.tag == 'string'}


class TranslationsTest(unittest.TestCase):
    def test_all_new_messages_have_matching_placeholders(self):
        base = strings(RES / 'values/plus_ui_strings.xml')
        placeholder = re.compile(r'%(\d+)\$[sd]')
        for language in LANGUAGES:
            translated = strings(RES / f'values-{language}/plus_ui_strings.xml')
            self.assertEqual(base.keys(), translated.keys(), language)
            for name, value in translated.items():
                self.assertEqual(Counter(placeholder.findall(base[name])), Counter(placeholder.findall(value)), (language, name))
                self.assertTrue(value.strip('"').strip(), (language, name))

    def test_existing_plus_overrides_are_valid(self):
        base = strings(RES / 'values/plus_strings.xml')
        placeholder = re.compile(r'%(\d+)\$[sd]')
        for language in LANGUAGES:
            for name, value in strings(RES / f'values-{language}/plus_strings.xml').items():
                self.assertIn(name, base)
                self.assertEqual(placeholder.findall(base[name]), placeholder.findall(value), (language, name))

    def test_xml_has_no_duplicate_resource_names(self):
        for directory in [RES / 'values', *(RES / ('values-' + lang) for lang in LANGUAGES)]:
            names = []
            for path in directory.glob('*.xml'):
                names.extend(item.attrib['name'] for item in ET.parse(path).getroot() if item.tag == 'string')
            self.assertEqual(len(names), len(set(names)), str(directory))


if __name__ == '__main__':
    unittest.main()
