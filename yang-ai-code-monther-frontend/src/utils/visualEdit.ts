/**
 * 可视化编辑工具：iframe 内网页元素的「悬浮高亮 + 点击选中 + 跨页面通信」。
 *
 * 设计（拆分逻辑，避免对话页臃肿）：
 *   - 对话页右栏预览 iframe 的 srcdoc 里注入一段编辑脚本（见 {@link buildEditorScript}）。
 *     脚本在 iframe 内部运行，不受父页面 sandbox 限制，通过 postMessage 与父页面通信；
 *   - 父页面（对话页）通过 {@link notifyEditMode} 通知 iframe 开启/关闭编辑；
 *     iframe 内点击选中元素后回传 {@link SelectedElement}（父页面用 {@link parseElementFromEvent} 接收）；
 *   - 发送消息时用 {@link buildElementPrompt} 把选中元素信息并入提示词，
 *     {@link formatElementLabel} 生成输入框上方 alert 的展示文案。
 *
 * 注意：编辑脚本只在「实时预览（srcdoc）」里可用。已部署 iframe 是跨域 nginx 站点，
 * 父页面无法注入脚本，编辑按钮在非 srcdoc 模式下禁用（由对话页控制）。
 */

/** 可视化编辑相关的 postMessage 事件名（父 ↔ iframe 共用） */
export const VE_EVENT = {
  /** 父 → iframe：开启编辑模式 */
  ENABLE: 'VISUAL_EDIT_ENABLE',
  /** 父 → iframe：关闭编辑模式（清除选中与高亮） */
  DISABLE: 'VISUAL_EDIT_DISABLE',
  /** 父 → iframe：仅清除选中项（保留编辑模式） */
  CLEAR: 'VISUAL_EDIT_CLEAR',
  /** iframe → 父：选中 / 取消元素。element 为 null 表示取消选中 */
  SELECT: 'VISUAL_EDIT_SELECT',
  /** 父 → iframe：把选中元素文字直接改成 d.text（所见即所得，不调 AI） */
  APPLY_TEXT: 'VISUAL_EDIT_APPLY_TEXT',
  /** 父 → iframe：把 d.style 里的样式属性直接应用到选中元素（颜色/内边距/外边距，不调 AI） */
  APPLY_STYLE: 'VISUAL_EDIT_APPLY_STYLE',
  /** 父 → iframe：把选中元素样式还原为选中时的原始内联样式（保存失败回滚用） */
  RESTORE_STYLE: 'VISUAL_EDIT_RESTORE_STYLE',
} as const

/** iframe 内选中的网页元素信息 */
export interface SelectedElement {
  /** 标签名，小写，如 'div' */
  tag: string
  /** 元素 id（无则空串） */
  id: string
  /** 完整 class 字符串（无则空串） */
  className: string
  /** 元素可见文本（截断到 60 字符，空白折叠，供展示） */
  text: string
  /** 完整可见文本（截断到 800 字符，供「直接改文字」编辑框预填/发给 AI） */
  fullText?: string
  /** 元素在代码里的原始内联 style 属性值（选中时的快照，样式保存失败回滚用） */
  inlineStyle?: string
  /** 到 body 的简易 CSS 路径，如 'main > form > button#submit' */
  path: string
}

/**
 * 生成注入 iframe 的编辑脚本（字符串）。
 * 脚本挂载后默认休眠，收到父页面 ENABLE 才激活高亮/选中；
 * 点击选中后把元素信息 postMessage 回父页面。
 * 不含 `</script>` 字面量，可直接嵌入 srcdoc。
 */
export function buildEditorScript(): string {
  return `(function () {
  if (window.__veInjected) return;
  window.__veInjected = true;
  var enabled = false;
  var selected = null;
  var hover = null;
  var origStyle = '';

  function info(el) {
    var parts = [];
    var n = el;
    while (n && n !== document.documentElement && n !== document.body && parts.length < 5) {
      var seg = n.tagName.toLowerCase();
      if (n.id) {
        seg += '#' + n.id;
      } else if (n.className && typeof n.className === 'string') {
        var cls = n.className.trim().split(/\\s+/).slice(0, 2).join('.');
        if (cls) seg += '.' + cls;
      }
      parts.unshift(seg);
      n = n.parentElement;
    }
    var raw = (el.textContent || '').replace(/\\s+/g, ' ').trim();
    var text = raw.slice(0, 60);
    return {
      tag: el.tagName.toLowerCase(),
      id: el.id || '',
      className: typeof el.className === 'string' ? el.className.trim() : '',
      text: text,
      fullText: raw.slice(0, 800),
      inlineStyle: el.getAttribute('style') || '',
      path: parts.join(' > ')
    };
  }

  function restoreStyle() {
    if (!selected) return;
    // 还原为选中时的原始内联样式（保存失败回滚）
    if (origStyle) {
      selected.setAttribute('style', origStyle);
    } else {
      selected.removeAttribute('style');
    }
  }

  function paint(el, color, width) {
    if (!el) return;
    el.style.outline = width ? width + 'px solid ' + color : '';
    el.style.outlineOffset = width ? '2px' : '';
  }

  function clearSelected() {
    if (selected) { paint(selected, '', 0); selected = null; }
  }

  function report(el) {
    try { window.parent.postMessage({ type: 'VISUAL_EDIT_SELECT', element: el }, '*'); } catch (e) {}
  }

  document.addEventListener('mouseover', function (e) {
    if (!enabled) return;
    var t = e.target;
    if (!t || t === document.documentElement || t === document.body) return;
    if (hover && hover !== selected) paint(hover, '', 0);
    hover = t;
    if (t !== selected) paint(t, '#38bdf8', 1);
  }, true);

  document.addEventListener('mouseout', function (e) {
    if (!enabled || !hover) return;
    if (e.relatedTarget instanceof Node && hover.contains(e.relatedTarget)) return;
    if (hover !== selected) paint(hover, '', 0);
    hover = null;
  }, true);

  document.addEventListener('click', function (e) {
    if (!enabled) return;
    e.preventDefault();
    e.stopPropagation();
    var t = e.target;
    if (!t || t === document.documentElement || t === document.body) return;
    if (selected === t) {
      clearSelected();
      report(null);
      return;
    }
    clearSelected();
    selected = t;
    origStyle = t.getAttribute('style') || '';
    paint(t, '#38bdf8', 2);
    report(info(t));
  }, true);

  window.addEventListener('message', function (e) {
    var d = e.data || {};
    if (d.type === 'VISUAL_EDIT_ENABLE') {
      enabled = true;
    } else if (d.type === 'VISUAL_EDIT_DISABLE') {
      enabled = false;
      clearSelected();
      report(null);
    } else if (d.type === 'VISUAL_EDIT_CLEAR') {
      clearSelected();
      report(null);
    } else if (d.type === 'VISUAL_EDIT_APPLY_TEXT') {
      // 所见即所得：直接把选中元素文字替换成 d.text（用户不调 AI 的本地修改）
      if (selected && typeof d.text === 'string') {
        selected.textContent = d.text;
        report(info(selected));
      }
    } else if (d.type === 'VISUAL_EDIT_APPLY_STYLE') {
      // 所见即所得：把样式属性直接应用到选中元素（颜色/内边距/外边距，不调 AI）
      // 值为空串表示清除该属性（与后端 serializeInlineStyle 跳过空值保持一致）
      if (selected && d.style && typeof d.style === 'object') {
        for (var k in d.style) {
          if (Object.prototype.hasOwnProperty.call(d.style, k)) {
            if (d.style[k]) {
              selected.style.setProperty(k, d.style[k]);
            } else {
              selected.style.removeProperty(k);
            }
          }
        }
        report(info(selected));
      }
    } else if (d.type === 'VISUAL_EDIT_RESTORE_STYLE') {
      restoreStyle();
      report(info(selected));
    }
  });
})();`
}

/**
 * 把编辑脚本注入到 srcdoc 里（插到 `</body>` 前；没有 body 则直接追加）。
 * 返回注入后的完整 doc 字符串。流式期间 / appUpdated 后重建 previewDoc 都经由此处。
 */
export function injectEditorScript(doc: string): string {
  if (!doc) return doc
  const scriptTag = '<script>' + buildEditorScript() + '</scr' + 'ipt>'
  if (/<\/body>/i.test(doc)) {
    return doc.replace(/<\/body>/i, scriptTag + '\n</body>')
  }
  return doc + scriptTag
}

/**
 * 父页面通知 iframe 开启/关闭编辑模式。
 * iframe 每次因 srcdoc 更新而重建后，父页面都应重新调用（脚本只在新文档里注册监听）。
 */
export function notifyEditMode(frame: HTMLIFrameElement | null, enabled: boolean) {
  if (!frame || !frame.contentWindow) return
  frame.contentWindow.postMessage({ type: enabled ? VE_EVENT.ENABLE : VE_EVENT.DISABLE }, '*')
}

/**
 * 父页面通知 iframe 仅清除选中项（保留编辑模式）。
 */
export function notifyClearSelection(frame: HTMLIFrameElement | null) {
  if (!frame || !frame.contentWindow) return
  frame.contentWindow.postMessage({ type: VE_EVENT.CLEAR }, '*')
}

/**
 * 父页面通知 iframe 把当前选中元素的文字直接替换为 text（所见即所得预览）。
 * 只是本地即时预览；真正落盘由「保存」按钮调后端替换接口完成。
 */
export function notifyApplyText(frame: HTMLIFrameElement | null, text: string) {
  if (!frame || !frame.contentWindow) return
  frame.contentWindow.postMessage({ type: VE_EVENT.APPLY_TEXT, text }, '*')
}

/**
 * 父页面通知 iframe 把样式属性直接应用到当前选中元素（所见即所得预览，不调 AI）。
 * 只应用传入的非空属性，未传的样式保持不变。
 */
export function notifyApplyStyle(frame: HTMLIFrameElement | null, style: Record<string, string>) {
  if (!frame || !frame.contentWindow) return
  frame.contentWindow.postMessage({ type: VE_EVENT.APPLY_STYLE, style }, '*')
}

/**
 * 父页面通知 iframe 把当前选中元素样式还原为选中时的原始内联样式（保存失败回滚用）。
 */
export function notifyRestoreStyle(frame: HTMLIFrameElement | null) {
  if (!frame || !frame.contentWindow) return
  frame.contentWindow.postMessage({ type: VE_EVENT.RESTORE_STYLE }, '*')
}

/**
 * 父页面解析 iframe 传来的消息。
 * 返回 undefined 表示非本功能消息（调用方应忽略）；
 * 返回 null 表示取消选中；返回 SelectedElement 表示选中了某个元素。
 */
export function parseElementFromEvent(
  e: MessageEvent,
  sourceWindow: Window | null,
): SelectedElement | null | undefined {
  // 只接受来自自己 iframe 的消息，避免其它窗口伪造
  if (e.source !== sourceWindow) return undefined
  const d = e.data
  if (!d || typeof d !== 'object' || d.type !== VE_EVENT.SELECT) return undefined
  return (d.element as SelectedElement | null) ?? null
}

/** 生成选中元素定位段（标签/id/类名/文本/路径），供各类元素修改提示词复用 */
function elementLocator(sel: SelectedElement, includeText = true): string[] {
  const lines = ['- 标签: <' + sel.tag + '>']
  if (sel.id) lines.push('- id: ' + sel.id)
  if (sel.className) lines.push('- 类名: ' + sel.className)
  if (includeText && sel.text) lines.push('- 文本: "' + sel.text + '"')
  if (sel.path) lines.push('- 路径: ' + sel.path)
  return lines
}

/**
 * 把选中元素信息并入发送给后端的提示词。
 * 无选中时原样返回 base。有选中时在 base 后追加元素定位段 + 小幅度修改约束，
 * 让 AI 只调整目标元素，不重写整个页面。
 */
export function buildElementPrompt(base: string, sel: SelectedElement | null): string {
  const trimmed = (base ?? '').trim()
  if (!sel) return trimmed
  const lines: string[] = [
    '页面元素（已选中，请针对该元素修改）:',
    ...elementLocator(sel),
    '- 只做小幅度局部修改：仅调整该元素本身，保持其其余属性与整体页面风格一致',
    '- 禁止重写、重构或改动页面其它部分；输出完整文件时，除目标元素外其它代码原样保留',
  ]
  const block = lines.join('\n')
  return trimmed ? trimmed + '\n\n' + block : block
}

/**
 * 生成输入框上方 alert 的选中元素展示文案（紧凑单行）。
 */
export function formatElementLabel(sel: SelectedElement): string {
  const parts = ['<' + sel.tag + '>']
  if (sel.id) parts.push('#' + sel.id)
  if (sel.className) {
    const cls = sel.className.trim().split(/\s+/).slice(0, 2).join('.')
    if (cls) parts.push('.' + cls)
  }
  if (sel.text) parts.push('"' + sel.text + '"')
  const head = parts.join(' ')
  return sel.path ? head + ' → ' + sel.path : head
}
