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

    fun propfindAddressBookRoot(tenantId: UUID, baseUrl: String): String {
        val doc = newDocument()
        val multistatus = doc.multistatusRoot()

        val tenantRoot = "$baseUrl/api/v1/carddav/$tenantId"
        val contactsUrl = "$tenantRoot/contacts/"

        // Principal resource — iOS reads current-user-principal and addressbook-home-set from here
        multistatus.appendChild(
            doc.response(
                href = "$tenantRoot/",
                propstats = listOf(
                    doc.propstat(
                        status = HTTP_200,
                        props = {
                            it.appendChild(doc.el(DAV, "resourcetype") {
                                appendChild(doc.el(DAV, "collection"))
                            })
                            it.appendChild(doc.el(DAV, "displayname") { textContent = "CRM Contacts" })
                            it.appendChild(doc.el(DAV, "current-user-principal") {
                                appendChild(doc.el(DAV, "href") { textContent = "$tenantRoot/" })
                            })
                            it.appendChild(doc.el(CARDDAV, "addressbook-home-set") {
                                appendChild(doc.el(DAV, "href") { textContent = contactsUrl })
                            })
                        }
                    )
                )
            )
        )

        // Address book collection
        multistatus.appendChild(
            doc.response(
                href = contactsUrl,
                propstats = listOf(
                    doc.propstat(
                        status = HTTP_200,
                        props = {
                            it.appendChild(doc.el(DAV, "resourcetype") {
                                appendChild(doc.el(DAV, "collection"))
                                appendChild(doc.el(CARDDAV, "addressbook"))
                            })
                            it.appendChild(doc.el(DAV, "displayname") { textContent = "CRM Contacts" })
                            it.appendChild(doc.el(DAV, "supported-report-set") {
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
        )

        return doc.toXmlString()
    }

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
                        doc.propstat(
                            status = HTTP_200,
                            props = {
                                it.appendChild(doc.el(DAV, "getetag") { textContent = "\"$etag\"" })
                                it.appendChild(doc.el(CARDDAV, "address-data") { textContent = vcard })
                            }
                        )
                    )
                )
            )
        }

        return doc.toXmlString()
    }

    fun propfindAddressBookCollection(tenantId: UUID, baseUrl: String): String =
        propfindAddressBookRoot(tenantId, baseUrl)

    // ── DOM helpers ──────────────────────────────────────────────────────────

    private fun newDocument(): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

    private fun Document.multistatusRoot(): Element {
        val root = createElementNS(DAV, "D:multistatus")
        root.setAttribute("xmlns:D", DAV)
        root.setAttribute("xmlns:C", CARDDAV)
        appendChild(root)
        return root
    }

    private fun Document.response(href: String, propstats: List<Element>): Element =
        el(DAV, "response") {
            appendChild(el(DAV, "href") { textContent = href })
            propstats.forEach { appendChild(it) }
        }

    private fun Document.propstat(status: String, props: (Element) -> Unit): Element =
        el(DAV, "propstat") {
            val prop = el(DAV, "prop") {}
            props(prop)
            appendChild(prop)
            appendChild(el(DAV, "status") { textContent = status })
        }

    private fun Document.el(ns: String, localName: String, init: Element.() -> Unit = {}): Element {
        val prefix = if (ns == DAV) "D" else "C"
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
        private const val HTTP_200 = "HTTP/1.1 200 OK"
    }
}
