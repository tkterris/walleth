package org.walleth.trezor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.squareup.wire.Message
import io.trezor.deviceprotocol.EthereumSignTx
import io.trezor.deviceprotocol.EthereumSignTxKeepKey
import io.trezor.deviceprotocol.EthereumTxRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.readByteString
import okio.ByteString.Companion.toByteString
import org.kethereum.extensions.transactions.encodeRLP
import org.kethereum.keccakshortcut.keccak
import org.kethereum.model.Address
import org.kethereum.model.SignatureData
import org.koin.android.ext.android.inject
import org.komputing.kbip44.BIP44
import org.komputing.khex.extensions.hexToByteArray
import org.komputing.khex.extensions.toHexString
import org.komputing.khex.model.HexString
import org.ligi.kaxtui.alert
import org.walleth.R
import org.walleth.R.string
import org.walleth.data.addresses.CurrentAddressProvider
import org.walleth.data.addresses.getTrezorDerivationPath
import org.walleth.data.transactions.TransactionState
import org.walleth.data.transactions.toEntity
import org.walleth.kethereum.android.TransactionParcel
import java.math.BigInteger

const val TREZOR_REQUEST_CODE = 7688

fun Activity.startTrezorActivity(transactionParcel: TransactionParcel) {
    val trezorIntent = Intent(this, TrezorSignTransactionActivity::class.java).putExtra("TX", transactionParcel)
    startActivityForResult(trezorIntent, TREZOR_REQUEST_CODE)
}

class TrezorSignTransactionActivity : BaseTrezorActivity() {

    private val transaction by lazy { intent.getParcelableExtra<TransactionParcel>("TX") }
    private val currentAddressProvider: CurrentAddressProvider by inject()

    override fun handleAddress(address: Address) {
        if (address != transaction.transaction.from) {
            val message = getString(R.string.trezor_reported_different_address, address, transaction.transaction.from)
            alert(message) {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        } else {
            enterNewState(STATES.PROCESS_TASK)
        }
    }

    private fun ByteArray.toByteString() = toByteString(0, size)
    override fun getTaskSpecificMessage() = if (isKeepKey) {
        EthereumSignTxKeepKey.Builder()
                //.to(transaction.transaction.to!!.hex)
                .to(HexString(transaction.transaction.to!!.hex).hexToByteArray().toByteString())
                .value(transaction.transaction.value!!.toByteArray().removeLeadingZero().toByteString())
                .nonce(transaction.transaction.nonce!!.toByteArray().removeLeadingZero().toByteString())
                .gas_price(transaction.transaction.gasPrice!!.toByteArray().removeLeadingZero().toByteString())
                .gas_limit(transaction.transaction.gasLimit!!.toByteArray().removeLeadingZero().toByteString())
                .chain_id(chainInfoProvider.value!!.chainId.toInt())
                .data_length(transaction.transaction.input.size)
                .data_initial_chunk(transaction.transaction.input.toByteString())
                .address_n(currentBIP44!!.path.map { it.numberWithHardeningFlag })
                .build()!!
    } else {
        EthereumSignTx.Builder()
                .to(transaction.transaction.to!!.hex)
                .value(transaction.transaction.value!!.toByteArray().removeLeadingZero().toByteString())
                .nonce(transaction.transaction.nonce!!.toByteArray().removeLeadingZero().toByteString())
                .gas_price(transaction.transaction.gasPrice!!.toByteArray().removeLeadingZero().toByteString())
                .gas_limit(transaction.transaction.gasLimit!!.toByteArray().removeLeadingZero().toByteString())
                .chain_id(chainInfoProvider.value!!.chainId.toInt())
                .data_length(transaction.transaction.input.size)
                .data_initial_chunk(transaction.transaction.input.toByteString())
                .address_n(currentBIP44!!.path.map { it.numberWithHardeningFlag })
                .build()!!
    }


    override fun handleExtraMessage(res: Message<*, *>?) {
        if (res is EthereumTxRequest) {

            val oldHash = transaction.transaction.txHash
            val signatureData = SignatureData(
                    r = BigInteger(res.signature_r.toByteArray()),
                    s = BigInteger(res.signature_s.toByteArray()),
                    v = res.signature_v.toBigInteger()
            )
            transaction.transaction.txHash = transaction.transaction.encodeRLP(signatureData).keccak().toHexString()
            lifecycleScope.launch(Dispatchers.Main) {
                withContext(Dispatchers.Default) {
                    appDatabase.runInTransaction {
                        oldHash?.let { appDatabase.transactions.deleteByHash(it) }
                        appDatabase.transactions.upsert(transaction.transaction.toEntity(signatureData, TransactionState()))
                    }
                }
                setResult(RESULT_OK, Intent().apply { putExtra("TXHASH", transaction.transaction.txHash) })
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.subtitle = getString(string.activity_subtitle_sign_with_trezor)

        lifecycleScope.launch {
            currentBIP44 = appDatabase.addressBook.byAddress(currentAddressProvider.getCurrentNeverNull())?.getTrezorDerivationPath()?.let { trezorDerivationPath ->
                BIP44(trezorDerivationPath)
            } ?: throw IllegalArgumentException("Starting TREZOR Activity without derivation path")

            connectAndExecute()
        }
    }

    private fun ByteArray.removeLeadingZero() = if (first() == 0.toByte()) copyOfRange(1, size) else this


}
