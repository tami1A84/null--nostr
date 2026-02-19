/**
 * Rust engine manager — disabled for web deployment.
 * nurunuru-napi is not used on the web server.
 * Native app support is handled via nurunuru-ffi (Step 10).
 *
 * All functions return null/false so that any remaining callers
 * gracefully fall through to their JS fallback paths.
 */

async function getOrCreateEngine() { return null }
async function loginUser() { return null }
function getCurrentLoginPubkey() { return null }
async function getRelayList() { return null }
async function addRelay() { return false }
async function removeRelay() { return false }
async function reconnectRelays() { return false }
async function getFollowList() { return null }
async function getMuteList() { return null }
async function fetchDms() { return null }
async function searchEvents() { return null }

module.exports = {
  getOrCreateEngine,
  loginUser,
  getCurrentLoginPubkey,
  getRelayList,
  addRelay,
  removeRelay,
  reconnectRelays,
  getFollowList,
  getMuteList,
  fetchDms,
  searchEvents,
}
