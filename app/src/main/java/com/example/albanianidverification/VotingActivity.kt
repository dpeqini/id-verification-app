package com.example.albanianidverification

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.albanianidverification.adapters.BallotAdapter
import com.example.albanianidverification.api.models.ElectionResponse
import com.example.albanianidverification.api.models.VoteRequest
import com.example.albanianidverification.api.models.VoteResponse
import com.example.albanianidverification.databinding.ActivityVotingBinding
import com.example.albanianidverification.security.ApiClient
import com.example.albanianidverification.security.KeyStoreManager
import com.example.albanianidverification.security.NonceManager
import com.example.albanianidverification.security.TokenManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * VotingActivity — the protected screen after successful biometric authentication.
 *
 * Launch sequence:
 *   1. GET /api/v1/elections/active          → pick the first STARTED election
 *   2. GET /api/v1/vote/status/{electionId}  → hasVoted?
 *        true  → show AlreadyVotedScreen (election stats only, no ballot)
 *        false → parallel:
 *                  GET /api/v1/elections/{electionId}/parties
 *                  GET /api/v1/vote/candidates/{electionId}   (region-filtered by backend)
 *                → show BallotScreen
 *   3. POST /api/v1/vote → show ReceiptScreen
 *
 * Region filtering is enforced by the backend:
 *   • LOCAL_GOVERNMENT → backend returns candidates in the voter's municipality only
 *   • PARLIAMENTARY    → backend returns candidates in the voter's county only
 * The adapter receives a nullable Set<String>:
 *   • null  = eligible list not loaded yet — show all (safe fallback)
 *   • empty = voter has no eligible candidates — show none (do not let empty == "all")
 *
 * Security:
 *   - JWT auto-injected by ApiClient interceptors
 *   - VoteRequest.encryptedVoteData = Base64(JSON payload)
 *   - VoteRequest.digitalSignature  = RSA-SHA256 via KeyStoreManager
 */
class VotingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VotingActivity"
        const val EXTRA_VOTER_ID        = "voter_id"
        const val EXTRA_VOTER_FULL_NAME = "voter_full_name"
    }

    private lateinit var binding: ActivityVotingBinding

    private var election: ElectionResponse? = null
    private var adapter: BallotAdapter? = null

    private var selectedPartyId: String?     = null
    private var selectedCandidateId: String? = null
    private var selectedCandidateName: String? = null
    private var selectedPartyName: String?   = null

    private var voteSubmitted = false
    private var voterId    = ""
    private var voterName  = ""

    // Full receipt values kept for clipboard operations
    private var receiptVoteHashFull: String?     = null
    private var receiptTokenFull: String?        = null
    private var receiptVerificationCode: String? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVotingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (TokenManager.getAccessToken() == null) {
            Toast.makeText(this, "Session expired. Please authenticate again.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        voterId   = intent.getStringExtra(EXTRA_VOTER_ID)        ?: ""
        voterName = intent.getStringExtra(EXTRA_VOTER_FULL_NAME) ?: ""
        binding.voterNameText.text = if (voterName.isNotBlank()) "Welcome, $voterName" else "Welcome"

        binding.logoutButton.setOnClickListener  { confirmLogout() }
        binding.castVoteButton.setOnClickListener { confirmVote() }
        binding.retryButton.setOnClickListener    { loadData() }

        // ── Receipt copy buttons ───────────────────────────────────────────
        // Tapping the hash text or the dedicated copy button copies the full hash.
        binding.receiptVoteHash.setOnClickListener { copyToClipboard("Vote Hash", receiptVoteHashFull) }
        binding.receiptToken.setOnClickListener    { copyToClipboard("Receipt Token", receiptTokenFull) }

        // If your layout has dedicated copy buttons, wire them here:
        // binding.copyVoteHashButton.setOnClickListener { copyToClipboard("Vote Hash", receiptVoteHashFull) }
        // binding.copyReceiptTokenButton.setOnClickListener { copyToClipboard("Receipt Token", receiptTokenFull) }

        // Hint that the fields are tappable (set in onCreate so it doesn't reset on re-render)
        binding.receiptVoteHash.hint  = "Tap to copy"
        binding.receiptToken.hint     = "Tap to copy"

        loadData()
    }

    override fun onBackPressed() {
        if (voteSubmitted) {
            AlertDialog.Builder(this)
                .setTitle("Exit Voting")
                .setMessage("Your vote has been recorded. You may exit safely.")
                .setPositiveButton("Exit") { _, _ -> super.onBackPressed() }
                .setNegativeButton("Stay", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadData() {
        show(Screen.LOADING)
        binding.loadingText.text = "Loading elections…"

        lifecycleScope.launch {
            try {
                val electionsResp = ApiClient.votingService.getActiveElections()
                if (!electionsResp.isSuccessful || electionsResp.body().isNullOrEmpty()) {
                    showError("No active elections found at this time.")
                    return@launch
                }
                val el = electionsResp.body()!!.first()
                election = el

                binding.loadingText.text = "Checking vote status…"
                val statusResp = ApiClient.votingService.getVoteStatus(el.id)
                if (statusResp.isSuccessful && statusResp.body()?.hasVoted == true) {
                    runOnUiThread { renderAlreadyVoted(el) }
                    return@launch
                }

                binding.loadingText.text = "Loading ballot…"
                coroutineScope {
                    val partiesD  = async { ApiClient.votingService.getParties(el.id) }
                    val eligibleD = async { ApiClient.votingService.getCandidatesForVoter(el.id) }

                    val partiesResp  = partiesD.await()
                    val eligibleResp = eligibleD.await()

                    if (!partiesResp.isSuccessful || partiesResp.body().isNullOrEmpty()) {
                        showError("Could not load ballot parties. Please try again.")
                        return@coroutineScope
                    }

                    val parties = partiesResp.body()!!

                    // Null  → eligible list could not be fetched (network error) → show all as fallback
                    // Empty → server returned 0 eligible candidates for this voter → show none
                    val eligibleIds: Set<String>? = eligibleResp.body()?.map { it.id }?.toSet()

                    runOnUiThread { renderBallot(el, parties, eligibleIds) }
                }

            } catch (e: Exception) {
                Log.e(TAG, "loadData failed", e)
                runOnUiThread { showError("Connection failed. Check your internet.") }
            }
        }
    }

    // ── Screen renderers ──────────────────────────────────────────────────────

    private fun renderBallot(
        el: ElectionResponse,
        parties: List<com.example.albanianidverification.api.models.PartyResponse>,
        eligibleIds: Set<String>?
    ) {
        show(Screen.BALLOT)

        binding.electionNameText.text   = el.name
        binding.electionTypeText.text   = formatType(el.electionType)
        binding.electionStatusText.text = "● ACTIVE"

        val totalCandidates = parties.sumOf { it?.candidateCount ?: 0 }
        binding.ballotSummaryText.text = "${parties.size} parties · $totalCandidates candidates"

        binding.selectionSummaryCard.visibility = View.GONE
        binding.castVoteButton.isEnabled = false

        // Pass eligibleIds as-is — the adapter handles null vs empty correctly
        adapter = BallotAdapter(parties, eligibleIds) { pId, cId, cName, pName ->
            selectedPartyId       = pId
            selectedCandidateId   = cId
            selectedCandidateName = cName
            selectedPartyName     = pName
            binding.selectionSummaryText.text = "✓ Selected: $cName — $pName"
            binding.selectionSummaryCard.visibility = View.VISIBLE
            binding.castVoteButton.isEnabled = true
        }

        binding.ballotRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.ballotRecyclerView.adapter = adapter
    }

    private fun renderAlreadyVoted(el: ElectionResponse) {
        show(Screen.ALREADY_VOTED)
        binding.avElectionName.text  = el.name
        binding.avElectionType.text  = formatType(el.electionType)
        binding.avElectionDate.text  = el.electionDate?.take(10)?.replace("T", " ") ?: ""
        binding.avTotalVotes.text    = "Total votes cast: ${el.totalVotesCast ?: "—"}"
        binding.avTurnout.text       = el.turnoutPercentage
            ?.let { "Turnout: ${"%.1f".format(it)}%" } ?: ""
        binding.avPartyCount.text    = "${el.partyCount ?: el.parties.size} parties participated"
    }

    private fun renderReceipt(r: VoteResponse) {
        voteSubmitted = true
        show(Screen.RECEIPT)

        // Store full values for clipboard
        receiptVoteHashFull     = r.voteHash
        receiptTokenFull        = r.receiptToken
        receiptVerificationCode = r.verificationCode

        binding.receiptElectionName.text  = r.electionName  ?: election?.name ?: ""
        binding.receiptCandidateName.text = r.candidateName ?: selectedCandidateName ?: "—"
        binding.receiptPartyName.text     = r.partyName     ?: selectedPartyName     ?: "—"
        binding.receiptTimestamp.text     = r.timestamp?.take(19)?.replace("T", " ") ?: "—"

        // Show truncated hash + copy hint
        if (r.voteHash != null) {
            binding.receiptVoteHash.text = "${r.voteHash.take(32)}…  📋"
        } else {
            binding.receiptVoteHash.text = "—"
        }

        // Show truncated token + copy hint
        if (r.receiptToken != null) {
            binding.receiptToken.text = "${r.receiptToken.take(24)}…  📋"
        } else {
            binding.receiptToken.text = "—"
        }

        // Verification code — short code the voter can use to look up their vote publicly
        if (r.verificationCode != null) {
            binding.receiptVerificationCode.visibility = View.VISIBLE
            binding.receiptVerificationCode.text = "Verification code: ${r.verificationCode}  📋"
            binding.receiptVerificationCode.setOnClickListener {
                copyToClipboard("Verification Code", receiptVerificationCode)
            }
        } else {
            // If the backend doesn't return a separate verification code, fall back to the hash
            binding.receiptVerificationCode.visibility = View.GONE
        }

        binding.receiptBlockchain.text = r.blockchainTransactionId
            ?.let { "Block #${r.blockNumber} · ${it.take(20)}…" }
            ?: "Recording on blockchain…"
    }

    private fun showError(msg: String) {
        runOnUiThread {
            show(Screen.ERROR)
            binding.errorText.text = msg
        }
    }

    // ── Voting ────────────────────────────────────────────────────────────────

    private fun confirmVote() {
        AlertDialog.Builder(this)
            .setTitle("Confirm Your Vote")
            .setMessage(
                "Candidate: $selectedCandidateName\nParty: $selectedPartyName\n\n" +
                        "⚠ This action cannot be undone. Your vote is final."
            )
            .setPositiveButton("Cast Vote") { _, _ -> submitVote() }
            .setNegativeButton("Go Back", null)
            .setCancelable(false)
            .show()
    }

    private fun submitVote() {
        val elId = election?.id        ?: return
        val cId  = selectedCandidateId ?: return
        val pId  = selectedPartyId     ?: return

        binding.castVoteButton.isEnabled = false
        show(Screen.LOADING)
        binding.loadingText.text = "Submitting your vote…"

        lifecycleScope.launch {
            try {
                val req  = buildVoteRequest(elId, cId, pId)
                val resp = ApiClient.votingService.castVote(req)

                when {
                    resp.isSuccessful && resp.body()?.success == true ->
                        runOnUiThread { renderReceipt(resp.body()!!) }

                    resp.code() == 409 ->
                        runOnUiThread {
                            renderAlreadyVoted(election!!)
                            Toast.makeText(this@VotingActivity,
                                "You have already voted in this election.", Toast.LENGTH_LONG).show()
                        }

                    resp.code() == 400 ->
                        runOnUiThread {
                            show(Screen.BALLOT)
                            binding.castVoteButton.isEnabled = true
                            Toast.makeText(this@VotingActivity,
                                "Vote rejected: candidate not eligible for your region.",
                                Toast.LENGTH_LONG).show()
                        }

                    else -> {
                        Log.w(TAG, "castVote HTTP ${resp.code()}: ${resp.errorBody()?.string()}")
                        runOnUiThread {
                            show(Screen.BALLOT)
                            binding.castVoteButton.isEnabled = true
                            Toast.makeText(this@VotingActivity,
                                "Failed to submit (${resp.code()}). Please try again.",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "castVote network error", e)
                runOnUiThread {
                    show(Screen.BALLOT)
                    binding.castVoteButton.isEnabled = true
                    Toast.makeText(this@VotingActivity,
                        "Network error. Check connection and try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Builds VoteRequest with:
     *   encryptedVoteData = Base64(JSON {electionId, candidateId, partyId, voterId, ts})
     *   digitalSignature  = RSA-SHA256(electionId:candidateId:partyId) via KeyStoreManager
     */
    private fun buildVoteRequest(elId: String, cId: String, pId: String): VoteRequest {
        val payload = JSONObject().apply {
            put("electionId",  elId)
            put("candidateId", cId)
            put("partyId",     pId)
            put("voterId",     voterId)
            put("timestamp",   System.currentTimeMillis())
        }.toString()
        val encodedPayload = Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP)

        val safeCandidateId = cId.ifEmpty { "NONE" }
        val safePartyId     = pId.ifEmpty { "NONE" }
        val payloadToSign   = "$elId:$safeCandidateId:$safePartyId"

        val rsaSignature = KeyStoreManager.signData(payloadToSign)
        val nonce        = NonceManager.generateNonce()

        return VoteRequest(
            electionId        = elId,
            candidateId       = cId,
            partyId           = pId,
            encryptedVoteData = encodedPayload,
            digitalSignature  = rsaSignature,
            nonce             = nonce
        )
    }

    // ── Clipboard ─────────────────────────────────────────────────────────────

    /**
     * Copies [value] to the system clipboard and shows a Toast confirmation.
     * The label is shown in password managers / clipboard managers.
     */
    private fun copyToClipboard(label: String, value: String?) {
        if (value.isNullOrBlank()) {
            Toast.makeText(this, "$label not available", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                TokenManager.clearTokens()
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private enum class Screen { LOADING, BALLOT, ALREADY_VOTED, RECEIPT, ERROR }

    private fun show(s: Screen) {
        binding.loadingScreen.visibility      = if (s == Screen.LOADING)       View.VISIBLE else View.GONE
        binding.ballotScreen.visibility       = if (s == Screen.BALLOT)        View.VISIBLE else View.GONE
        binding.alreadyVotedScreen.visibility = if (s == Screen.ALREADY_VOTED) View.VISIBLE else View.GONE
        binding.receiptScreen.visibility      = if (s == Screen.RECEIPT)       View.VISIBLE else View.GONE
        binding.errorScreen.visibility        = if (s == Screen.ERROR)         View.VISIBLE else View.GONE
    }

    private fun formatType(t: String) = when (t) {
        "PARLIAMENTARY"    -> "Parliamentary Election"
        "LOCAL_GOVERNMENT" -> "Local Government Election"
        else               -> t
    }
}