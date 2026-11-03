#!/usr/bin/env python3
"""Extract a read-only XLSX snapshot into the website. Standard library only.

Usage: python3 scripts/import-experiment-workbook.py input.xlsx [output.js]
No workbook edits, no inferred paths, no fabricated source revision.
"""
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

NS = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
FIELDS = ['config', 'instance', 'size', 'status', 'length', 'timeMs', 'generated',
          'expanded', 'openPeak', 'closed', 'processPeakMb', 'engine', 'heuristic', 'variant']


def read_workbook(path):
    with zipfile.ZipFile(path) as archive:
        strings = []
        if 'xl/sharedStrings.xml' in archive.namelist():
            strings = [''.join(n.itertext()) for n in ET.fromstring(archive.read('xl/sharedStrings.xml'))]
        relationships = {n.attrib['Id']: n.attrib['Target'] for n in
                         ET.fromstring(archive.read('xl/_rels/workbook.xml.rels'))}
        sheets = {}
        for sheet in ET.fromstring(archive.read('xl/workbook.xml')).find('s:sheets', NS):
            relation = sheet.attrib['{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id']
            target = relationships[relation]
            file = target.lstrip('/') if target.startswith('/') else 'xl/' + target
            rows = []
            for row in ET.fromstring(archive.read(file)).findall('.//s:sheetData/s:row', NS):
                cells = {}
                for cell in row:
                    address = cell.attrib['r']
                    if cell.find('s:f', NS) is not None:
                        raise ValueError('Formula requires independent recalculation: ' + address)
                    value = cell.find('s:v', NS)
                    text = value.text if value is not None else None
                    kind = cell.attrib.get('t')
                    if kind == 's': text = strings[int(text)]
                    elif kind == 'inlineStr': text = ''.join(cell.find('s:is', NS).itertext())
                    elif kind == 'e': raise ValueError('Spreadsheet error: ' + address)
                    elif text is not None:
                        text = float(text) if any(t in text.lower() for t in ['.', 'e']) else int(text)
                    if text is not None: cells[address] = text
                if cells: rows.append(cells)
            sheets[sheet.attrib['name']] = rows
        return sheets


def build(path):
    sheets = read_workbook(path)
    raw = sheets['06 原始数据']
    if list(raw[2].values()) != ['配置', '实例号', '阶数', '状态', '最优步数', '时间(ms)',
                                '生成', '扩展', 'Open峰值', 'Closed', '进程峰值(MB)', '引擎', '启发函数', '变体']:
        raise ValueError('Unexpected source columns')
    records = []
    issues = []
    seen = set()
    for row in raw[3:]:
        number = int(re.search(r'\d+', next(iter(row))).group())
        values = {re.sub(r'\d+', '', key): value for key, value in row.items()}
        record = {field: values.get(chr(65+i)) for i, field in enumerate(FIELDS)}
        record['sourceRow'] = number
        if record['status'] not in ['solved', 'timeout', 'oom']:
            raise ValueError('Unknown result status at row ' + str(number))
        if record['size'] not in [3, 4]: raise ValueError('Unexpected board size')
        for field in ['length', 'generated', 'expanded', 'openPeak', 'closed', 'processPeakMb']:
            value = record[field]
            if value == -1: record[field] = None  # source sentinel: unmeasured, not zero
            elif value is not None and (not isinstance(value, (float, int)) or not math.isfinite(value) or value < 0):
                raise ValueError('Invalid metric: ' + field)
        if record['status'] != 'solved':
            record['reportedTimeMs'] = record['timeMs']
            record['timeMs'] = None  # timeout boundary is not a solved duration
            if record['reportedTimeMs'] and record['status'] == 'timeout':
                record['timeoutLimitMs'] = record['reportedTimeMs']
        elif not isinstance(record['timeMs'], (int, float)) or not math.isfinite(record['timeMs']) or record['timeMs'] < 0:
            raise ValueError('Missing solved duration')
        if record['instance'] is None:
            issues.append({'sourceRow': number, 'config': record['config'],
                           'reason': '缺少实例号，保留原记录但不加入按实例比较；失败耗时不作为成功耗时。'})
        else:
            key = (record['config'], record['instance'])
            if key in seen: raise ValueError('Duplicate instance: ' + str(key))
            seen.add(key)
        records.append(record)
    notes = {re.sub(r'\d+', '', k): v for row in sheets['00 说明'] for k, v in row.items() if k.startswith('C6')}
    # Preserve source cells for provenance; do not infer a run-specific code commit.
    metadata = {'sourceFile': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'rawSheet': '06 原始数据', 'recordCount': len(records),
                'sourceRevision': None, 'codeReviewedRevision': 'a461d3b',
                'environment': notes.get('C'), 'timing': 'JVM search() 墙钟；预热 1 次，重复 3–5 次取中位数',
                'notes': sheets['00 说明'], 'issues': issues}
    # Keep source summary cells as audit evidence rather than recreating rounded headline values.
    summaries = {name: rows for name, rows in sheets.items() if name.startswith(('01 ', '02 ', '03 ', '04 ', '05 '))}
    return {'schemaVersion': 1, 'metadata': metadata, 'records': records, 'sourceSummaries': summaries}


if __name__ == '__main__':
    source = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2] if len(sys.argv) > 2 else 'visualization/lab-data.js')
    data = build(source)
    output.write_text('// Generated from the read-only workbook; see scripts/import-experiment-workbook.py.\n'
                      + 'window.NPuzzleLabData = ' + json.dumps(data, ensure_ascii=False, separators=(',', ':'), allow_nan=False) + ';\n', encoding='utf-8')
    print(f"Extracted {len(data['records'])} rows; {len(data['metadata']['issues'])} unassigned failures; SHA256 {data['metadata']['sha256']}")
