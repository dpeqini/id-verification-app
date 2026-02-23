package com.example.albanianidverification

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
 *                  GET /api/v1/vote/candidates/{electionId}   (region-filtered)
 *                → show BallotScreen
 *   3. POST /api/v1/vote → show ReceiptScreen
 *
 * Security:
 *   - JWT auto-injected by ApiClient interceptors on every call
 *   - VoteRequest.encryptedVoteData = Base64(JSON payload)
 *   - VoteRequest.digitalSignature  = HMAC-SHA256(encryptedVoteData) via NonceManager
 *   - Back press disabled after vote submitted
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

    // Selection state kept here (mirrored from adapter callbacks)
    private var selectedPartyId: String?     = null
    private var selectedCandidateId: String? = null
    private var selectedCandidateName: String? = null
    private var selectedPartyName: String?   = null

    private var voteSubmitted = false
    private var voterId    = ""
    private var voterName  = ""

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
                // Step 1: active elections
                val electionsResp = ApiClient.votingService.getActiveElections()
                if (!electionsResp.isSuccessful || electionsResp.body().isNullOrEmpty()) {
                    showError("No active elections found at this time.")
                    return@launch
                }
                val el = electionsResp.body()!!.first()
                election = el

                // Step 2: vote status
                binding.loadingText.text = "Checking vote status…"
                val statusResp = ApiClient.votingService.getVoteStatus(el.id)
                if (statusResp.isSuccessful && statusResp.body()?.hasVoted == true) {
                    runOnUiThread { renderAlreadyVoted(el) }
                    return@launch
                }

                // Step 3: parties + eligible candidates (parallel)
                binding.loadingText.text = "Loading ballot…"
                coroutineScope {
                    val partiesD    = async { ApiClient.votingService.getParties(el.id) }
                    val eligibleD   = async { ApiClient.votingService.getCandidatesForVoter(el.id) }

                    val partiesResp  = partiesD.await()
                    val eligibleResp = eligibleD.await()

                    if (!partiesResp.isSuccessful || partiesResp.body().isNullOrEmpty()) {
                        showError("Could not load ballot parties. Please try again.")
                        return@coroutineScope
                    }

                    val parties  = partiesResp.body()!!
                    val eligible = eligibleResp.body() ?: emptyList()

                    // Build a set of eligible candidate IDs so the adapter can grey out ineligible ones
                    val eligibleIds = eligible.map { it.id }.toSet()

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
        eligibleIds: Set<String>
    ) {
        show(Screen.BALLOT)

        binding.electionNameText.text  = el.name
        binding.electionTypeText.text  = formatType(el.electionType)
        binding.electionStatusText.text = "● ACTIVE"

        val totalCandidates = parties.sumOf { it?.candidateCount!! }
        binding.ballotSummaryText.text = "${parties.size} parties · $totalCandidates candidates"

        binding.selectionSummaryCard.visibility = View.GONE
        binding.castVoteButton.isEnabled = false

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
        binding.receiptElectionName.text  = r.electionName  ?: election?.name ?: ""
        binding.receiptCandidateName.text = r.candidateName ?: selectedCandidateName ?: "—"
        binding.receiptPartyName.text     = r.partyName     ?: selectedPartyName     ?: "—"
        binding.receiptVoteHash.text      = r.voteHash?.let { "${it.take(32)}…" }    ?: "—"
        binding.receiptToken.text         = r.receiptToken?.let { "${it.take(24)}…" } ?: "—"
        binding.receiptBlockchain.text    = r.blockchainTransactionId
            ?.let { "Block #${r.blockNumber} · ${it.take(20)}…" }
            ?: "Recording on blockchain…"
        binding.receiptTimestamp.text = r.timestamp?.take(19)?.replace("T", " ") ?: "—"
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
        val elId = election?.id   ?: return
        val cId  = selectedCandidateId ?: return
        val pId  = selectedPartyId     ?: return

        binding.castVoteButton.isEnabled = false
        show(Screen.LOADING)
        binding.loadingText.text = "Submitting your vote…"

        lifecycleScope.launch {
            try {
                val req = buildVoteRequest(elId, cId, pId)
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
     *   digitalSignature  = HMAC-SHA256(encryptedVoteData) via NonceManager
     */
// Replace your existing buildVoteRequest method in VotingActivity.kt
    private fun buildVoteRequest(elId: String, cId: String, pId: String): VoteRequest {
        // 1. Create the JSON payload for the backend to store
        val payload = JSONObject().apply {
            put("electionId",  elId)
            put("candidateId", cId)
            put("partyId",     pId)
            put("voterId",     voterId)
            put("timestamp",   System.currentTimeMillis())
        }.toString()
        val encodedPayload = Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP)

        // 2. Create the EXACT string the backend will try to verify
        // Using "NONE" if an ID is empty to prevent null-pointer mismatches
        val safeCandidateId = cId.ifEmpty { "NONE" }
        val safePartyId = pId.ifEmpty { "NONE" }
        val payloadToSign = "$elId:$safeCandidateId:$safePartyId"

        // 3. Sign the string using the hardware RSA Private Key
        val rsaSignature = KeyStoreManager.signData(payloadToSign)

        // 4. Wrap it up with a fresh nonce for the API transport layer
        val nonce = NonceManager.generateNonce()

        return VoteRequest(
            electionId        = elId,
            candidateId       = cId,
            partyId           = pId,
            encryptedVoteData = encodedPayload,
            digitalSignature  = rsaSignature, // <-- Now contains the RSA signature
            nonce             = nonce
        )
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                TokenManager.clearTokens()
                // Navigate explicitly to MainActivity and clear the back stack.
                // Just calling finish() would go back to wherever Android left off,
                // which may be an empty task or the wrong screen.
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
        binding.loadingScreen.visibility     = if (s == Screen.LOADING)       View.VISIBLE else View.GONE
        binding.ballotScreen.visibility      = if (s == Screen.BALLOT)        View.VISIBLE else View.GONE
        binding.alreadyVotedScreen.visibility = if (s == Screen.ALREADY_VOTED) View.VISIBLE else View.GONE
        binding.receiptScreen.visibility     = if (s == Screen.RECEIPT)       View.VISIBLE else View.GONE
        binding.errorScreen.visibility       = if (s == Screen.ERROR)         View.VISIBLE else View.GONE
    }

    private fun formatType(t: String) = when (t) {
        "PARLIAMENTARY"    -> "Parliamentary Election"
        "LOCAL_GOVERNMENT" -> "Local Government Election"
        else               -> t
    }
}