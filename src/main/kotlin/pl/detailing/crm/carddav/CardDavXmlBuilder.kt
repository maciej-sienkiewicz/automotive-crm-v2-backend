package pl.detailing.crm.carddav

import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.Element
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import java.io.StringWriter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@Component
class CardDavXmlBuilder {

    // Step 1 of iOS discovery: principal resource exposing current-user-principal
    // and addressbook-home-set. iOS sends PROPFIND Depth:0 to the account URL first.
    fun propfindPrincipal(tenantId: UUID, baseUrl: String): String {
        val doc = newDocument()
        val multistatus = doc.multistatusRoot()
        val tenantRoot = "$baseUrl/api/v1/carddav/$tenantId"
        val contactsUrl = "$tenantRoot/contacts/"

        multistatus.appendChild(
            doc.response(
                href = "$tenantRoot/",
                propstats = listOf(
                    doc.propstat(HTTP_200) {
                        appendChild(doc.el(DAV, "resourcetype") {
                            appendChild(doc.el(DAV, "collection"))
                        })
                        appendChild(doc.el(DAV, "displayname") { textContent = "CRM Contacts" })
                        appendChild(doc.el(DAV, "current-user-principal") {
                            appendChild(doc.el(DAV, "href") { textContent = "$tenantRoot/" })
                        })
                        appendChild(doc.el(CARDDAV, "addressbook-home-set") {
                            appendChild(doc.el(DAV, "href") { textContent = contactsUrl })
                        })
                    }
                )
            )
        )

        return doc.toXmlString()
    }

    // Step 2 of iOS discovery: PROPFIND on the addressbook collection URL.
    // Depth:0 returns only collection metadata; Depth:1 also includes per-card ETags
    // so iOS can detect which cards changed without fetching full vCard data.
    fun propfindAddressBook(
        tenantId: UUID,
        customers: List<CustomerEntity>,
        vCardFormatter: VCardFormatter,
        baseUrl: String,
        depth: String
    ): String {
        val doc = newDocument()
        val multistatus = doc.multistatusRoot()
        val tenantRoot = "$baseUrl/api/v1/carddav/$tenantId"
        val contactsUrl = "$tenantRoot/contacts/"
        val syncToken = computeSyncToken(tenantId, customers)

        multistatus.appendChild(
            doc.response(
                href = contactsUrl,
                propstats = listOf(
                    doc.propstat(HTTP_200) {
                        appendChild(doc.el(DAV, "resourcetype") {
                            appendChild(doc.el(DAV, "collection"))
                            appendChild(doc.el(CARDDAV, "addressbook"))
                        })
                        appendChild(doc.el(DAV, "displayname") { textContent = "CRM Contacts" })
                        appendChild(doc.el(DAV, "sync-token") { textContent = syncToken })
                        appendChild(doc.el(CALENDARSERVER, "getctag") { textContent = syncToken })
                        appendChild(doc.el(CARDDAV, "supported-address-data") {
                            appendChild(doc.el(CARDDAV, "address-data-type").also {
                                it.setAttribute("content-type", "text/vcard")
                                it.setAttribute("version", "3.0")
                            })
                        })
                        appendChild(doc.el(DAV, "supported-report-set") {
                            appendChild(doc.el(DAV, "supported-report") {
                                appendChild(doc.el(DAV, "report") {
                                    appendChild(doc.el(CARDDAV, "addressbook-query"))
                                })
                            })
                            appendChild(doc.el(DAV, "supported-report") {
                                appendChild(doc.el(DAV, "report") {
                                    appendChild(doc.el(CARDDAV, "addressbook-multiget"))
                                })
                            })
                        })
                    }
                )
            )
        )

        if (depth == "1") {
            for (customer in customers) {
                val href = "$tenantRoot/contacts/${customer.id}.vcf"
                val etag = vCardFormatter.etag(customer)
                multistatus.appendChild(
                    doc.response(
                        href = href,
                        propstats = listOf(
                            doc.propstat(HTTP_200) {
                                appendChild(doc.el(DAV, "getetag") { textContent = "\"$etag\"" })
                                appendChild(doc.el(DAV, "resourcetype") {})
                            }
                        )
                    )
                )
            }
        }

        return doc.toXmlString()
    }

    // PROPFIND on a single .vcf resource — returns ETag for conditional GET / multiget.
    fun propfindSingleContact(
        tenantId: UUID,
        customer: CustomerEntity,
        vCardFormatter: VCardFormatter,
        baseUrl: String
    ): String {
        val doc = newDocument()
        val multistatus = doc.multistatusRoot()
        val href = "$baseUrl/api/v1/carddav/$tenantId/contacts/${customer.id}.vcf"
        val etag = vCardFormatter.etag(customer)

        multistatus.appendChild(
            doc.response(
                href = href,
                propstats = listOf(
                    doc.propstat(HTTP_200) {
                        appendChild(doc.el(DAV, "getetag") { textContent = "\"$etag\"" })
                        appendChild(doc.el(DAV, "resourcetype") {})
                    }
                )
            )
        )

        return doc.toXmlString()
    }

    // REPORT addressbook-query / addressbook-multiget — full vCard payload per contact.
    fun reportAddressBookQuery(
        tenantId: UUID,
        customers: List<CustomerEntity>,
        vCardFormatter: VCardFormatter,
        baseUrl: String
    ): String {
        val doc = newDocument()
        val multistatus = doc.multistatusRoot()

        for (customer in customers) {
            val vcard = vCardFormatter.format(customer)
            val etag = vCardFormatter.etag(customer)
            val href = "$baseUrl/api/v1/carddav/$tenantId/contacts/${customer.id}.vcf"

            multistatus.appendChild(
                doc.response(
                    href = href,
                    propstats = listOf(
                        doc.propstat(HTTP_200) {
                            appendChild(doc.el(DAV, "getetag") { textContent = "\"$etag\"" })
                            appendChild(doc.el(CARDDAV, "address-data") { textContent = vcard })
                        }
                    )
                )
            )
        }

        return doc.toXmlString()
    }

    // ── Sync token ───────────────────────────────────────────────────────────

    // Changes when any contact is added, removed, or updated. iOS uses this to
    // decide whether a re-sync is needed (via getctag comparison or sync-token).
    private fun computeSyncToken(tenantId: UUID, customers: List<CustomerEntity>): String {
        val maxUpdated = customers.maxOfOrNull { it.updatedAt.toEpochMilli() } ?: 0L
        return "$tenantId:${customers.size}:$maxUpdated"
    }

    // ── DOM helpers ──────────────────────────────────────────────────────────

    private fun newDocument(): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

    private fun Document.multistatusRoot(): Element {
        val root = createElementNS(DAV, "D:multistatus")
        root.setAttribute("xmlns:D", DAV)
        root.setAttribute("xmlns:C", CARDDAV)
        root.setAttribute("xmlns:CS", CALENDARSERVER)
        appendChild(root)
        return root
    }

    private fun Document.response(href: String, propstats: List<Element>): Element =
        el(DAV, "response") {
            appendChild(el(DAV, "href") { textContent = href })
            propstats.forEach { appendChild(it) }
        }

    private fun Document.propstat(status: String, props: Element.() -> Unit): Element =
        el(DAV, "propstat") {
            val prop = el(DAV, "prop") {}
            prop.props()
            appendChild(prop)
            appendChild(el(DAV, "status") { textContent = status })
        }

    private fun Document.el(ns: String, localName: String, init: Element.() -> Unit = {}): Element {
        val prefix = when (ns) {
            DAV -> "D"
            CARDDAV -> "C"
            CALENDARSERVER -> "CS"
            else -> "X"
        }
        return createElementNS(ns, "$prefix:$localName").also { it.init() }
    }

    private fun Document.toXmlString(): String {
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }.transform(DOMSource(this), StreamResult(writer))
        return writer.toString()
    }

    companion object {
        private const val DAV = "DAV:"
        private const val CARDDAV = "urn:ietf:params:xml:ns:carddav"
        private const val CALENDARSERVER = "http://calendarserver.org/ns/"
        private const val HTTP_200 = "HTTP/1.1 200 OK"
    }
}
