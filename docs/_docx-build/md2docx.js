/* Minimal Markdown -> DOCX converter for the Beauty Filter SDK docs.
 * Usage: node md2docx.js manifest.json
 * Handles: #..#### headings, ``` code fences, | tables |, blockquotes,
 *          - / * bullet lists, 1. ordered lists, --- rules, **bold**, `code`,
 *          [text](link) (rendered as text). Plain lines are joined into paragraphs.
 */
const fs = require('fs');
const path = require('path');
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  AlignmentType, LevelFormat, TableOfContents, HeadingLevel, BorderStyle,
  WidthType, ShadingType, PageNumber, PageBreak, Header, Footer, VerticalAlign,
} = require('docx');

const CONTENT_WIDTH = 9360; // US Letter, 1" margins

const manifestPath = process.argv[2];
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const baseDir = path.dirname(path.resolve(manifestPath));

// Fonts: when manifest.font is set (e.g. Japanese), cover the eastAsia slot too
// so CJK glyphs render with the intended typeface instead of a fallback.
const SANS = manifest.font
  ? { ascii: manifest.font, hAnsi: manifest.font, eastAsia: manifest.font }
  : 'Arial';
const MONO = manifest.font
  ? { ascii: 'Consolas', hAnsi: 'Consolas', eastAsia: manifest.font }
  : 'Consolas';

// Color palette (overridable per manifest to match a house style).
const P = Object.assign({
  title: '1F3864', subtitle: '2E4A7A',
  heading1: '1F3864', heading2: '2E4A7A', heading3: '365F91', heading4: '4A4A4A',
  tableHeaderFill: 'E8EEF4', tableBorder: 'BBBBBB',
  codeFill: 'F4F4F6', inlineCodeFill: 'F0F0F2',
  calloutFill: 'F6F7F9', calloutBorder: 'C0C7D0',
  rule: 'CCCCCC', coverText: '444444', footer: '888888',
}, manifest.palette || {});

// ---- numbering configs (unique per ordered list to restart counters) -------
const numberingConfigs = [{
  reference: 'bullets',
  levels: [
    { level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT,
      style: { paragraph: { indent: { left: 480, hanging: 240 } } } },
    { level: 1, format: LevelFormat.BULLET, text: '◦', alignment: AlignmentType.LEFT,
      style: { paragraph: { indent: { left: 960, hanging: 240 } } } },
    { level: 2, format: LevelFormat.BULLET, text: '▪', alignment: AlignmentType.LEFT,
      style: { paragraph: { indent: { left: 1440, hanging: 240 } } } },
  ],
}];
let orderedSeq = 0;
function newOrderedRef() {
  const ref = 'ol-' + (orderedSeq++);
  numberingConfigs.push({
    reference: ref,
    levels: [
      { level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 480, hanging: 280 } } } },
      { level: 1, format: LevelFormat.LOWER_LETTER, text: '%2.', alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 960, hanging: 280 } } } },
      { level: 2, format: LevelFormat.LOWER_ROMAN, text: '%3.', alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 1440, hanging: 280 } } } },
    ],
  });
  return ref;
}

// ---- inline parsing: **bold**, `code`, [text](url) -------------------------
function inlineRuns(text, baseOpts = {}) {
  const runs = [];
  // tokenize on `code` first, then ** within non-code
  const re = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\[[^\]]+\]\([^)]+\))/g;
  let last = 0, m;
  const pushPlain = (s) => {
    if (!s) return;
    runs.push(new TextRun({ text: s, font: SANS, ...baseOpts }));
  };
  while ((m = re.exec(text)) !== null) {
    pushPlain(text.slice(last, m.index));
    const tok = m[0];
    if (tok.startsWith('`')) {
      runs.push(new TextRun({ text: tok.slice(1, -1), font: MONO, size: 18,
        shading: { type: ShadingType.CLEAR, fill: P.inlineCodeFill }, ...baseOpts }));
    } else if (tok.startsWith('**')) {
      runs.push(new TextRun({ text: tok.slice(2, -2), font: SANS, bold: true, ...baseOpts }));
    } else { // link [text](url) -> text only
      const lt = tok.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
      pushPlain(lt[1]);
    }
    last = re.lastIndex;
  }
  pushPlain(text.slice(last));
  if (runs.length === 0) runs.push(new TextRun({ text: '', font: SANS, ...baseOpts }));
  return runs;
}

// ---- block builders --------------------------------------------------------
function codeBlock(lines) {
  return lines.map((ln, i) => new Paragraph({
    shading: { type: ShadingType.CLEAR, fill: P.codeFill },
    spacing: { before: i === 0 ? 60 : 0, after: i === lines.length - 1 ? 60 : 0, line: 240 },
    indent: { left: 120, right: 120 },
    keepLines: true, keepNext: i < lines.length - 1,
    children: [new TextRun({ text: ln === '' ? ' ' : ln, font: MONO, size: 18 })],
  }));
}

function ruleParagraph() {
  return new Paragraph({
    border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: P.rule, space: 1 } },
    spacing: { before: 120, after: 120 },
    children: [new TextRun({ text: '', font: SANS })],
  });
}

function tableBlock(rows, aligns) {
  const ncol = rows[0].length;
  const colW = Math.floor(CONTENT_WIDTH / ncol);
  const widths = Array(ncol).fill(colW);
  widths[ncol - 1] += CONTENT_WIDTH - colW * ncol;
  const border = { style: BorderStyle.SINGLE, size: 1, color: P.tableBorder };
  const borders = { top: border, bottom: border, left: border, right: border };
  const trows = rows.map((cells, ri) =>
    new TableRow({
      tableHeader: ri === 0,
      children: cells.map((c, ci) => new TableCell({
        borders,
        width: { size: widths[ci], type: WidthType.DXA },
        shading: ri === 0 ? { type: ShadingType.CLEAR, fill: P.tableHeaderFill } : undefined,
        margins: { top: 60, bottom: 60, left: 110, right: 110 },
        verticalAlign: VerticalAlign.CENTER,
        children: [new Paragraph({
          alignment: aligns[ci] || AlignmentType.LEFT,
          spacing: { after: 0, line: 240 },
          children: inlineRuns(c, ri === 0 ? { bold: true } : {}),
        })],
      })),
    }));
  return new Table({ width: { size: CONTENT_WIDTH, type: WidthType.DXA }, columnWidths: widths, rows: trows });
}

// ---- markdown -> blocks ----------------------------------------------------
function parseMarkdown(md, firstSection) {
  const out = [];
  const lines = md.replace(/\r\n/g, '\n').split('\n');
  let i = 0;
  let para = [];
  const flushPara = () => {
    if (para.length) {
      out.push(new Paragraph({ spacing: { after: 120, line: 276 },
        children: inlineRuns(para.join(' ')) }));
      para = [];
    }
  };
  let firstHeadingSeen = false;
  while (i < lines.length) {
    let line = lines[i];

    // code fence
    if (/^\s*```/.test(line)) {
      flushPara();
      const buf = [];
      i++;
      while (i < lines.length && !/^\s*```/.test(lines[i])) { buf.push(lines[i]); i++; }
      i++; // skip closing fence
      codeBlock(buf).forEach(p => out.push(p));
      continue;
    }

    // heading
    let hm = line.match(/^(#{1,6})\s+(.*)$/);
    if (hm) {
      flushPara();
      const level = hm[1].length;
      const text = hm[2].trim();
      const headingLevel = [HeadingLevel.HEADING_1, HeadingLevel.HEADING_2,
        HeadingLevel.HEADING_3, HeadingLevel.HEADING_4, HeadingLevel.HEADING_5,
        HeadingLevel.HEADING_6][level - 1];
      const pageBreakBefore = level === 1 && !(firstSection && !firstHeadingSeen);
      out.push(new Paragraph({
        heading: headingLevel,
        pageBreakBefore,
        children: inlineRuns(text),
      }));
      firstHeadingSeen = true;
      i++;
      continue;
    }

    // horizontal rule
    if (/^\s*(---+|\*\*\*+|___+)\s*$/.test(line)) {
      flushPara();
      out.push(ruleParagraph());
      i++;
      continue;
    }

    // table
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length &&
        /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
      flushPara();
      const splitRow = (l) => l.trim().replace(/^\|/, '').replace(/\|$/, '')
        .split('|').map(s => s.trim());
      const header = splitRow(line);
      const sep = splitRow(lines[i + 1]);
      const aligns = sep.map(s => {
        const l = s.startsWith(':'), r = s.endsWith(':');
        if (l && r) return AlignmentType.CENTER;
        if (r) return AlignmentType.RIGHT;
        return AlignmentType.LEFT;
      });
      const rows = [header];
      i += 2;
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) {
        rows.push(splitRow(lines[i])); i++;
      }
      out.push(tableBlock(rows, aligns));
      out.push(new Paragraph({ spacing: { after: 80 }, children: [new TextRun({ text: '', font: SANS })] }));
      continue;
    }

    // blockquote
    if (/^\s*>/.test(line)) {
      flushPara();
      const buf = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        buf.push(lines[i].replace(/^\s*>\s?/, '')); i++;
      }
      out.push(new Paragraph({
        indent: { left: 200, right: 120 },
        border: {
          left: { style: BorderStyle.SINGLE, size: 24, color: P.calloutBorder, space: 10 },
          top: { style: BorderStyle.SINGLE, size: 2, color: P.calloutBorder, space: 4 },
          bottom: { style: BorderStyle.SINGLE, size: 2, color: P.calloutBorder, space: 4 },
          right: { style: BorderStyle.SINGLE, size: 2, color: P.calloutBorder, space: 4 },
        },
        shading: { type: ShadingType.CLEAR, fill: P.calloutFill },
        spacing: { before: 80, after: 140, line: 276 },
        children: inlineRuns(buf.join(' ')),
      }));
      continue;
    }

    // unordered list
    let um = line.match(/^(\s*)[-*]\s+(.*)$/);
    if (um) {
      flushPara();
      while (i < lines.length) {
        const lm = lines[i].match(/^(\s*)[-*]\s+(.*)$/);
        if (!lm) break;
        const level = Math.min(2, Math.floor(lm[1].length / 2));
        out.push(new Paragraph({
          numbering: { reference: 'bullets', level },
          spacing: { after: 40, line: 276 },
          children: inlineRuns(lm[2]),
        }));
        i++;
      }
      continue;
    }

    // ordered list
    let om = line.match(/^(\s*)\d+\.\s+(.*)$/);
    if (om) {
      flushPara();
      const ref = newOrderedRef();
      while (i < lines.length) {
        const lm = lines[i].match(/^(\s*)\d+\.\s+(.*)$/);
        if (!lm) break;
        const level = Math.min(2, Math.floor(lm[1].length / 3));
        out.push(new Paragraph({
          numbering: { reference: ref, level },
          spacing: { after: 40, line: 276 },
          children: inlineRuns(lm[2]),
        }));
        i++;
      }
      continue;
    }

    // blank line
    if (/^\s*$/.test(line)) { flushPara(); i++; continue; }

    // plain text line -> accumulate
    para.push(line.trim());
    i++;
  }
  flushPara();
  return out;
}

// ---- assemble document -----------------------------------------------------
const body = [];

// cover page
body.push(new Paragraph({ spacing: { before: 2600, after: 0 },
  children: [new TextRun({ text: manifest.title, font: SANS, bold: true, size: 56, color: P.title })] }));
body.push(new Paragraph({ spacing: { before: 160, after: 0 },
  children: [new TextRun({ text: manifest.subtitle, font: SANS, size: 30, color: P.subtitle })] }));
body.push(new Paragraph({
  border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: P.title, space: 6 } },
  spacing: { before: 240, after: 240 }, children: [new TextRun({ text: '', font: SANS })] }));
manifest.coverLines.forEach(l =>
  body.push(new Paragraph({ spacing: { after: 60 },
    children: [new TextRun({ text: l, font: SANS, size: 22, color: P.coverText })] })));

// TOC
body.push(new Paragraph({ pageBreakBefore: true, heading: HeadingLevel.HEADING_1,
  children: [new TextRun({ text: manifest.tocTitle, font: SANS })] }));
body.push(new TableOfContents(manifest.tocTitle, { hyperlink: true, headingStyleRange: '1-3' }));

// sections
manifest.sections.forEach((rel, idx) => {
  const md = fs.readFileSync(path.join(baseDir, rel), 'utf8');
  parseMarkdown(md, false).forEach(b => body.push(b));
});

const heading = (id, name, size, outline, color) => ({
  id, name, basedOn: 'Normal', next: 'Normal', quickFormat: true,
  run: { size, bold: true, font: SANS, color },
  paragraph: { spacing: { before: outline === 0 ? 320 : 240, after: 120 }, outlineLevel: outline,
    keepNext: true },
});

const doc = new Document({
  creator: 'BeautyFilter SDK Docs',
  title: manifest.title,
  styles: {
    default: { document: { run: { font: SANS, size: 21 } } },
    paragraphStyles: [
      heading('Heading1', 'Heading 1', 34, 0, P.heading1),
      heading('Heading2', 'Heading 2', 27, 1, P.heading2),
      heading('Heading3', 'Heading 3', 23, 2, P.heading3),
      heading('Heading4', 'Heading 4', 21, 3, P.heading4),
    ],
  },
  numbering: { config: numberingConfigs },
  sections: [{
    properties: { page: {
      size: { width: 12240, height: 15840 },
      margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 },
    } },
    footers: { default: new Footer({ children: [new Paragraph({
      alignment: AlignmentType.CENTER,
      children: [
        new TextRun({ text: manifest.footer + '   |   ', font: SANS, size: 16, color: P.footer }),
        new TextRun({ children: [PageNumber.CURRENT], font: SANS, size: 16, color: P.footer }),
      ],
    })] }) },
    children: body,
  }],
});

Packer.toBuffer(doc).then(buf => {
  fs.writeFileSync(path.join(baseDir, manifest.output), buf);
  console.log('wrote', manifest.output, buf.length, 'bytes');
});
