package com.example.server

import com.example.model.FileCategory
import com.example.model.ServerConfig
import com.example.model.SharedFileItem
import com.example.util.FormatUtils

object WebPortalHtmlBuilder {

    fun buildPinGateHtml(serverName: String, hasError: Boolean): String {
        val escapedName = escapeHtml(serverName)
        val errorBanner = if (hasError) {
            """<div class="err">Incorrect PIN. Please check the LanDrop app and try again.</div>"""
        } else {
            ""
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>$escapedName — Enter PIN</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  background: #0B1120;
                  color: #F1F5F9;
                  min-height: 100vh;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  padding: 20px;
                }
                .card {
                  width: 100%;
                  max-width: 390px;
                  background: #11192C;
                  border: 1px solid #233152;
                  border-radius: 20px;
                  padding: 32px 24px;
                  text-align: center;
                  box-shadow: 0 20px 50px rgba(0,0,0,0.4);
                }
                .badge {
                  display: inline-block;
                  padding: 6px 14px;
                  border-radius: 999px;
                  background: rgba(0,210,180,0.14);
                  color: #00D2B4;
                  font-size: 12px;
                  font-weight: 700;
                  letter-spacing: 0.08em;
                  margin-bottom: 16px;
                }
                h1 { font-size: 22px; margin-bottom: 8px; }
                p { font-size: 14px; color: #94A3B8; margin-bottom: 24px; }
                input {
                  width: 100%;
                  padding: 14px 16px;
                  border-radius: 12px;
                  border: 1px solid #2E3D5E;
                  background: #0B1120;
                  color: #F1F5F9;
                  font-size: 22px;
                  letter-spacing: 0.35em;
                  text-align: center;
                  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                  outline: none;
                  margin-bottom: 16px;
                }
                input:focus { border-color: #00D2B4; }
                button {
                  width: 100%;
                  padding: 14px;
                  border: none;
                  border-radius: 12px;
                  background: #00D2B4;
                  color: #00201B;
                  font-size: 15px;
                  font-weight: 700;
                  cursor: pointer;
                }
                button:hover { opacity: 0.92; }
                .err {
                  background: rgba(255,92,114,0.15);
                  border: 1px solid rgba(255,92,114,0.4);
                  color: #FF8FA0;
                  padding: 10px 12px;
                  border-radius: 10px;
                  font-size: 13px;
                  margin-bottom: 16px;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="badge">PROTECTED SERVER</div>
                <h1>$escapedName</h1>
                <p>Enter the PIN shown in the LanDrop app to view and download shared files.</p>
                $errorBanner
                <form method="GET" action="/auth">
                  <input type="password" inputmode="numeric" name="pin" placeholder="••••" maxlength="6" autofocus required />
                  <button type="submit">Unlock Files</button>
                </form>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    fun buildPortalHtml(
        config: ServerConfig,
        files: List<SharedFileItem>,
    ): String {
        val escapedTitle = escapeHtml(config.serverName)
        val totalSize = FormatUtils.formatBytes(files.sumOf { it.sizeBytes.coerceAtLeast(0L) })

        val zipButton = if (files.size >= 2) {
            """<a class="btn btn-secondary" href="/download-all.zip">Download All (.zip)</a>"""
        } else {
            ""
        }

        val uploadSection = if (config.allowBrowserUploads) {
            """
            <section class="upload-box" id="dropZone">
              <div class="upload-head">
                <div>
                  <h2>Send Files Back to Phone</h2>
                  <p class="muted">Drop files here or click to upload directly over local Wi-Fi</p>
                </div>
                <label class="btn btn-outline">
                  Choose Files
                  <input type="file" id="uploadInput" multiple hidden />
                </label>
              </div>
              <div id="uploadStatus" class="upload-status"></div>
            </section>
            """.trimIndent()
        } else {
            ""
        }

        val fileRows = if (files.isEmpty()) {
            """
            <div class="empty">
              <div class="empty-icon">📡</div>
              <h3>No files shared yet</h3>
              <p class="muted">Pick files inside the LanDrop app on the phone, then click Refresh.</p>
            </div>
            """.trimIndent()
        } else {
            files.joinToString("\n") { item ->
                val safeName = escapeHtml(item.name)
                val sizeLabel = FormatUtils.formatBytes(item.sizeBytes)
                val badge = categoryBadge(item.category)
                val previewable = isPreviewable(item)
                val previewBtn = if (previewable) {
                    """<a class="btn btn-ghost" href="/preview/${item.id}" target="_blank" rel="noopener">Preview</a>"""
                } else {
                    ""
                }
                """
                <article class="file-card" data-name="${safeName.lowercase()}">
                  <div class="file-meta">
                    <span class="cat-pill">${badge.first} ${badge.second}</span>
                    <div class="file-info">
                      <div class="file-name" title="$safeName">$safeName</div>
                      <div class="file-sub">$sizeLabel • ${escapeHtml(item.mimeType)}</div>
                    </div>
                  </div>
                  <div class="file-actions">
                    $previewBtn
                    <a class="btn btn-primary" href="/download/${item.id}">Download</a>
                  </div>
                </article>
                """.trimIndent()
            }
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>$escapedTitle — Local Wi-Fi Share</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                :root {
                  --bg: #0B1120;
                  --surface: #11192C;
                  --surface-hi: #1A2540;
                  --border: #263554;
                  --text: #F1F5F9;
                  --muted: #94A3B8;
                  --teal: #00D2B4;
                  --cyan: #38BDF8;
                }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  background: radial-gradient(circle at top right, rgba(0,210,180,0.09), transparent 45%), var(--bg);
                  color: var(--text);
                  min-height: 100vh;
                  padding: 20px 16px 48px;
                }
                .wrap { max-width: 860px; margin: 0 auto; }
                header {
                  background: var(--surface);
                  border: 1px solid var(--border);
                  border-radius: 20px;
                  padding: 22px;
                  margin-bottom: 18px;
                  display: flex;
                  flex-wrap: wrap;
                  gap: 16px;
                  align-items: center;
                  justify-content: space-between;
                }
                .live-pill {
                  display: inline-flex;
                  align-items: center;
                  gap: 8px;
                  background: rgba(0,210,180,0.14);
                  color: var(--teal);
                  padding: 5px 12px;
                  border-radius: 999px;
                  font-size: 11px;
                  font-weight: 700;
                  letter-spacing: 0.08em;
                  margin-bottom: 8px;
                }
                .dot {
                  width: 8px;
                  height: 8px;
                  border-radius: 50%;
                  background: var(--teal);
                  box-shadow: 0 0 10px var(--teal);
                }
                h1 { font-size: 24px; letter-spacing: -0.02em; }
                h2 { font-size: 16px; margin-bottom: 4px; }
                .muted { color: var(--muted); font-size: 13px; }
                .header-actions { display: flex; gap: 10px; flex-wrap: wrap; }
                .toolbar {
                  display: flex;
                  gap: 10px;
                  margin-bottom: 14px;
                  align-items: center;
                }
                .search {
                  flex: 1;
                  padding: 12px 14px;
                  border-radius: 12px;
                  border: 1px solid var(--border);
                  background: var(--surface);
                  color: var(--text);
                  font-size: 14px;
                  outline: none;
                }
                .search:focus { border-color: var(--teal); }
                .file-list { display: flex; flex-direction: column; gap: 10px; }
                .file-card {
                  background: var(--surface);
                  border: 1px solid var(--border);
                  border-radius: 16px;
                  padding: 14px 16px;
                  display: flex;
                  flex-wrap: wrap;
                  gap: 12px;
                  align-items: center;
                  justify-content: space-between;
                  transition: border-color 0.15s ease;
                }
                .file-card:hover { border-color: rgba(0,210,180,0.45); }
                .file-meta { display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1; }
                .cat-pill {
                  background: var(--surface-hi);
                  color: var(--cyan);
                  padding: 8px 10px;
                  border-radius: 10px;
                  font-size: 12px;
                  font-weight: 700;
                  white-space: nowrap;
                }
                .file-info { min-width: 0; }
                .file-name {
                  font-weight: 600;
                  font-size: 15px;
                  white-space: nowrap;
                  overflow: hidden;
                  text-overflow: ellipsis;
                  max-width: 440px;
                }
                .file-sub { color: var(--muted); font-size: 12px; margin-top: 2px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
                .file-actions { display: flex; gap: 8px; }
                .btn {
                  display: inline-flex;
                  align-items: center;
                  justify-content: center;
                  padding: 9px 15px;
                  border-radius: 10px;
                  font-size: 13px;
                  font-weight: 700;
                  text-decoration: none;
                  cursor: pointer;
                  border: none;
                }
                .btn-primary { background: var(--teal); color: #00201B; }
                .btn-secondary { background: rgba(56,189,248,0.16); color: var(--cyan); border: 1px solid rgba(56,189,248,0.35); }
                .btn-outline { background: transparent; color: var(--text); border: 1px solid var(--border); }
                .btn-ghost { background: var(--surface-hi); color: var(--text); }
                .btn:hover { filter: brightness(1.08); }
                .upload-box {
                  margin-top: 22px;
                  background: var(--surface);
                  border: 1.5px dashed var(--border);
                  border-radius: 18px;
                  padding: 18px 20px;
                  transition: border-color 0.15s ease, background 0.15s ease;
                }
                .upload-box.drag { border-color: var(--teal); background: rgba(0,210,180,0.06); }
                .upload-head { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; justify-content: space-between; }
                .upload-status { margin-top: 12px; font-size: 13px; color: var(--teal); display: flex; flex-direction: column; gap: 6px; }
                .empty {
                  background: var(--surface);
                  border: 1px solid var(--border);
                  border-radius: 18px;
                  padding: 42px 20px;
                  text-align: center;
                }
                .empty-icon { font-size: 36px; margin-bottom: 10px; }
                @media (max-width: 600px) {
                  .file-name { max-width: 220px; }
                  .file-actions { width: 100%; }
                  .file-actions .btn { flex: 1; }
                }
              </style>
            </head>
            <body>
              <div class="wrap">
                <header>
                  <div>
                    <div class="live-pill"><span class="dot"></span>LOCAL WI-FI SERVER</div>
                    <h1>$escapedTitle</h1>
                    <p class="muted">${files.size} file(s) shared • $totalSize total • Direct LAN transfer</p>
                  </div>
                  <div class="header-actions">
                    <button class="btn btn-outline" onclick="window.location.reload()">Refresh</button>
                    $zipButton
                  </div>
                </header>

                <div class="toolbar">
                  <input class="search" id="searchInput" type="search" placeholder="Filter shared files..." />
                </div>

                <main class="file-list" id="fileList">
                  $fileRows
                </main>

                $uploadSection
              </div>

              <script>
                const searchInput = document.getElementById('searchInput');
                if (searchInput) {
                  searchInput.addEventListener('input', () => {
                    const q = searchInput.value.trim().toLowerCase();
                    document.querySelectorAll('.file-card').forEach(card => {
                      const name = card.getAttribute('data-name') || '';
                      card.style.display = name.includes(q) ? '' : 'none';
                    });
                  });
                }

                const uploadInput = document.getElementById('uploadInput');
                const dropZone = document.getElementById('dropZone');
                const uploadStatus = document.getElementById('uploadStatus');

                async function uploadFiles(fileList) {
                  if (!fileList || fileList.length === 0) return;
                  for (const file of fileList) {
                    const row = document.createElement('div');
                    row.textContent = 'Uploading ' + file.name + ' (0%)...';
                    uploadStatus.appendChild(row);
                    await new Promise((resolve) => {
                      const xhr = new XMLHttpRequest();
                      xhr.open('POST', '/upload?name=' + encodeURIComponent(file.name), true);
                      xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
                      xhr.upload.onprogress = (e) => {
                        if (e.lengthComputable) {
                          const pct = Math.round((e.loaded / e.total) * 100);
                          row.textContent = 'Uploading ' + file.name + ' (' + pct + '%)...';
                        }
                      };
                      xhr.onload = () => {
                        if (xhr.status >= 200 && xhr.status < 300) {
                          row.textContent = '✓ Sent ' + file.name + ' to phone';
                        } else {
                          row.style.color = '#FF8FA0';
                          row.textContent = '✕ Failed to send ' + file.name + ' (HTTP ' + xhr.status + ')';
                        }
                        resolve();
                      };
                      xhr.onerror = () => {
                        row.style.color = '#FF8FA0';
                        row.textContent = '✕ Network error sending ' + file.name;
                        resolve();
                      };
                      xhr.send(file);
                    });
                  }
                }

                if (uploadInput) {
                  uploadInput.addEventListener('change', (e) => {
                    uploadFiles(e.target.files);
                    uploadInput.value = '';
                  });
                }
                if (dropZone) {
                  dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('drag'); });
                  dropZone.addEventListener('dragleave', () => dropZone.classList.remove('drag'));
                  dropZone.addEventListener('drop', (e) => {
                    e.preventDefault();
                    dropZone.classList.remove('drag');
                    uploadFiles(e.dataTransfer.files);
                  });
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun isPreviewable(item: SharedFileItem): Boolean {
        return item.category in setOf(
            FileCategory.IMAGE,
            FileCategory.VIDEO,
            FileCategory.AUDIO,
            FileCategory.TEXT,
        ) || item.mimeType.equals("application/pdf", ignoreCase = true)
    }

    private fun categoryBadge(category: FileCategory): Pair<String, String> = when (category) {
        FileCategory.IMAGE -> "IMG" to "Photo"
        FileCategory.VIDEO -> "VID" to "Video"
        FileCategory.AUDIO -> "AUD" to "Audio"
        FileCategory.DOCUMENT -> "DOC" to "Doc"
        FileCategory.ARCHIVE -> "ZIP" to "Archive"
        FileCategory.APP -> "APK" to "App"
        FileCategory.TEXT -> "TXT" to "Text"
        FileCategory.OTHER -> "FILE" to "File"
    }

    private fun escapeHtml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
