import axios from 'axios'

export const api = axios.create({
  // dev에서는 Vite proxy를 쓰므로 baseURL은 비워두거나 '/'
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
})
