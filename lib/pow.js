/**
 * NIP-13 Proof of Work Library
 *
 * イベントIDの先頭ゼロビット数で難易度を計算
 * Quadratic Votingの重み付けに使用
 *
 * 参考: https://github.com/nostr-protocol/nips/blob/master/13.md
 *
 * @module pow
 */

import { getEventHash } from 'nostr-tools'

/**
 * Count the number of leading zero bits in a hex string
 * @param {string} hex - Hexadecimal string (event id)
 * @returns {number} Number of leading zero bits
 */
export function countLeadingZeroBits(hex) {
  let count = 0

  for (let i = 0; i < hex.length; i++) {
    const nibble = parseInt(hex[i], 16)
    if (nibble === 0) {
      count += 4
    } else {
      // Count leading zeros in this nibble
      count += Math.clz32(nibble) - 28
      break
    }
  }

  return count
}

/**
 * Get the difficulty (number of leading zero bits) of an event
 * @param {object} event - Nostr event with id
 * @returns {number} Difficulty (number of leading zero bits)
 */
export function getEventDifficulty(event) {
  if (!event.id) return 0
  return countLeadingZeroBits(event.id)
}

/**
 * Get the target difficulty from the nonce tag
 * @param {object} event - Nostr event
 * @returns {number|null} Target difficulty or null if not set
 */
export function getTargetDifficulty(event) {
  const nonceTag = event.tags?.find(t => t[0] === 'nonce')
  if (!nonceTag || nonceTag.length < 3) return null
  return parseInt(nonceTag[2], 10)
}

/**
 * Verify that an event meets its claimed difficulty
 * @param {object} event - Nostr event
 * @returns {boolean} True if event meets its target difficulty
 */
export function verifyPow(event) {
  const targetDifficulty = getTargetDifficulty(event)
  if (targetDifficulty === null) {
    // No PoW requirement
    return true
  }

  const actualDifficulty = getEventDifficulty(event)
  return actualDifficulty >= targetDifficulty
}

/**
 * Mine an event to achieve a target difficulty
 *
 * @param {object} event - Unsigned event (without id)
 * @param {number} targetDifficulty - Target number of leading zero bits
 * @param {object} options - Mining options
 * @param {number} options.maxIterations - Maximum mining attempts (default: 10_000_000)
 * @param {function} options.onProgress - Progress callback (iteration, hashRate)
 * @param {AbortSignal} options.signal - Abort signal to cancel mining
 * @returns {Promise<object>} Event with nonce and id meeting difficulty
 */
export async function mineEvent(event, targetDifficulty, options = {}) {
  const {
    maxIterations = 10_000_000,
    onProgress = null,
    signal = null
  } = options

  // Add nonce tag
  const eventToMine = {
    ...event,
    tags: [
      ...event.tags,
      ['nonce', '0', String(targetDifficulty)]
    ]
  }

  const startTime = Date.now()
  let lastProgressTime = startTime

  for (let nonce = 0; nonce < maxIterations; nonce++) {
    if (signal?.aborted) {
      throw new Error('Mining aborted')
    }

    // Update nonce
    const nonceTagIndex = eventToMine.tags.findIndex(t => t[0] === 'nonce')
    eventToMine.tags[nonceTagIndex][1] = String(nonce)

    // Update created_at periodically to avoid timestamp issues
    if (nonce % 100000 === 0) {
      eventToMine.created_at = Math.floor(Date.now() / 1000)
    }

    // Calculate hash
    const id = getEventHash(eventToMine)
    const difficulty = countLeadingZeroBits(id)

    // Progress callback
    if (onProgress && nonce % 10000 === 0) {
      const now = Date.now()
      const elapsed = (now - lastProgressTime) / 1000
      const hashRate = 10000 / elapsed
      onProgress(nonce, hashRate)
      lastProgressTime = now

      // Yield to prevent blocking
      await new Promise(resolve => setTimeout(resolve, 0))
    }

    if (difficulty >= targetDifficulty) {
      return {
        ...eventToMine,
        id
      }
    }
  }

  throw new Error(`Failed to mine event after ${maxIterations} iterations`)
}

// ============================================================================
// Quadratic Voting with PoW
// ============================================================================

/**
 * Difficulty levels for Quadratic Voting
 *
 * 難易度と投票の重みの関係:
 * - 難易度0-7:   1票 (PoWなし)
 * - 難易度8-11:  2票 (簡単なPoW)
 * - 難易度12-15: 3票 (中程度のPoW)
 * - 難易度16-19: 4票 (困難なPoW)
 * - 難易度20+:   5票 (非常に困難なPoW)
 */
export const DIFFICULTY_LEVELS = [
  { minDifficulty: 0, votes: 1, label: 'なし', estimatedTime: '即時' },
  { minDifficulty: 8, votes: 2, label: '簡単', estimatedTime: '数秒' },
  { minDifficulty: 12, votes: 3, label: '中程度', estimatedTime: '数十秒' },
  { minDifficulty: 16, votes: 4, label: '困難', estimatedTime: '数分' },
  { minDifficulty: 20, votes: 5, label: '非常に困難', estimatedTime: '十数分' }
]

/**
 * Get vote weight based on PoW difficulty
 *
 * Quadratic Voting的な考え方:
 * - より多くの票を投じるには、より多くの「コスト」(計算時間)が必要
 * - 強い意見を持つ人は、より多くのPoWを行うことで影響力を増やせる
 *
 * @param {number} difficulty - Event difficulty (leading zero bits)
 * @returns {number} Vote weight (1-5)
 */
export function getVoteWeightFromPow(difficulty) {
  for (let i = DIFFICULTY_LEVELS.length - 1; i >= 0; i--) {
    if (difficulty >= DIFFICULTY_LEVELS[i].minDifficulty) {
      return DIFFICULTY_LEVELS[i].votes
    }
  }
  return 1
}

/**
 * Get recommended difficulty for desired vote weight
 * @param {number} desiredVotes - Desired number of votes (1-5)
 * @returns {number} Recommended difficulty
 */
export function getRecommendedDifficulty(desiredVotes) {
  const level = DIFFICULTY_LEVELS.find(l => l.votes >= desiredVotes)
  return level?.minDifficulty ?? 0
}

/**
 * Get difficulty level info
 * @param {number} difficulty
 * @returns {object} Level info
 */
export function getDifficultyLevel(difficulty) {
  for (let i = DIFFICULTY_LEVELS.length - 1; i >= 0; i--) {
    if (difficulty >= DIFFICULTY_LEVELS[i].minDifficulty) {
      return DIFFICULTY_LEVELS[i]
    }
  }
  return DIFFICULTY_LEVELS[0]
}

/**
 * Calculate total weighted votes from a list of opinions
 *
 * @param {object[]} opinions - List of opinion events
 * @param {function} voteValueFn - Function to extract vote value from opinion
 * @returns {object} Weighted vote counts { agree, disagree, pass, total }
 */
export function calculateWeightedVotes(opinions, voteValueFn = defaultVoteValue) {
  let agree = 0
  let disagree = 0
  let pass = 0

  for (const opinion of opinions) {
    const voteValue = voteValueFn(opinion)
    const weight = getVoteWeightFromPow(getEventDifficulty(opinion))

    if (voteValue === 'agree') {
      agree += weight
    } else if (voteValue === 'disagree') {
      disagree += weight
    } else if (voteValue === 'pass') {
      pass += weight
    }
  }

  return {
    agree,
    disagree,
    pass,
    total: agree + disagree + pass
  }
}

function defaultVoteValue(opinion) {
  return opinion.tags?.find(t => t[0] === 'opinion')?.[1]
}

// ============================================================================
// Combined Weight: PoW + WoT
// ============================================================================

/**
 * Calculate combined vote weight from PoW and WoT
 *
 * 最終的な重み = PoW重み × WoT重み
 *
 * PoW重み: 1-5 (計算コストに基づく)
 * WoT重み: 0.1-1.0 (信頼スコアに基づく)
 *
 * 例:
 * - PoW難易度16 + WoTスコア10 → 4 × 1.0 = 4.0
 * - PoW難易度8 + WoTスコア0 → 2 × 0.1 = 0.2
 *
 * @param {number} powDifficulty
 * @param {number} wotScore
 * @param {number} maxWot
 * @returns {number} Combined weight
 */
export function getCombinedVoteWeight(powDifficulty, wotScore, maxWot = 10) {
  const powWeight = getVoteWeightFromPow(powDifficulty)

  // WoT weight: 0.1 to 1.0
  let wotWeight = 1.0
  if (wotScore !== Infinity) {
    if (wotScore <= 0) {
      wotWeight = 0.1
    } else {
      wotWeight = 0.1 + Math.min(wotScore / maxWot, 1.0) * 0.9
    }
  }

  return powWeight * wotWeight
}
