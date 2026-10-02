export async function api(path, options = {}) {
  const isForm = options.body instanceof FormData
  const response = await fetch(`/api${path}`, {
    ...options,
    credentials: 'same-origin',
    headers: {
      ...(options.body && !isForm ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers,
    },
  })
  if (response.status === 204) return null
  const text = await response.text()
  let payload
  try { payload = text ? JSON.parse(text) : null } catch { payload = null }
  if (!response.ok) {
    const error = new Error(payload?.detail || payload?.message || `请求失败 (${response.status})`)
    error.status = response.status
    throw error
  }
  return payload
}

export const post = (path, body) => api(path, { method: 'POST', body: JSON.stringify(body) })
export const patch = (path, body) => api(path, { method: 'PATCH', body: JSON.stringify(body) })

export async function download(path, filename) {
  const response = await fetch(`/api${path}`, {
    credentials: 'same-origin',
  })
  if (!response.ok) throw new Error(`下载失败 (${response.status})`)
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.append(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
