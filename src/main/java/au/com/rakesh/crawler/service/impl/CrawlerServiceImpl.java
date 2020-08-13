package au.com.rakesh.crawler.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.cache.annotation.CacheResult;
import javax.inject.Named;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;

import au.com.rakesh.crawler.configuration.AppProperties.CrawlerProperties;
import au.com.rakesh.crawler.model.PageInfo;
import au.com.rakesh.crawler.model.PageTreeInfo;
import au.com.rakesh.crawler.service.CrawlerService;
import lombok.extern.slf4j.Slf4j;

/**
 * @author buddy
 */
@Slf4j
@Named
public class CrawlerServiceImpl implements CrawlerService {

    @Value("#{appProperties.crawler}")
    private CrawlerProperties crawlerProperties;
    private String mainTitle;

    /*
     * recursive crawler to fetch child pages upto desired depth / max depth
     * (non-Javadoc)
     *
     * @see au.com.rakesh.crawler.service.CrawlerService#deepCrawl(java.lang.String,
     * int)
     */
    @Override
    @CacheResult(cacheName = "web-crawler-service")
    public PageTreeInfo deepCrawl(final String url, final int depth, final List<String> processedUrls, boolean first) {

        log.debug("Starting crawler for url {} for depth {}", url, depth);
        if (depth < 0) {
            log.info("Maximum depth reached, backing out for url {}", url);
            return null;
        } else {
            final List<String> updatedProcessedUrls = Optional.ofNullable(processedUrls).orElse(new ArrayList<>());
            final PageTreeInfo pageTreeInfo = new PageTreeInfo(url);
            if (!updatedProcessedUrls.contains(url)) {
                updatedProcessedUrls.add(url);
                crawl(url).ifPresent(pageInfo -> {
                    mainTitle = pageInfo.getTitle();
                    pageTreeInfo.title(mainTitle).valid(true)
                            .fieldOfStudy(pageInfo.getFieldOfStudy())
                            .publishedDate(pageInfo.getPublishedDate())
                            .authors(pageInfo.getAuthors())
                            .description(pageInfo.getDescription())
                            .pdfURL(pageInfo.getPdfURL());
//                            .flexRowPaperMeta(pageInfo.getFlexRowPaperMeta());
                    log.info("Found {} links on the web page: {}", pageInfo.getLinks().size(), url);
                    pageInfo.getLinks().forEach(link -> {
                        String nextUrl = link.attr("abs:href");
                        if (nextUrl.contains("paper") || nextUrl.contains("doi.org")) {
                            pageTreeInfo.addNodesItem(deepCrawl(nextUrl, depth - 1, updatedProcessedUrls, false));
                        }
                    });
                });
                return pageTreeInfo.getNodes() != null ? pageTreeInfo : null;
            } else {
                return null;
            }
        }

    }

    /*
     * Method to fetch web page content. Cache is used for better performance
     *
     * @see au.com.rakesh.crawler.service.CrawlerService#crawl(java.lang.String)
     */
    @Override
    @CacheResult(cacheName = "web-crawler-service")
    public Optional<PageInfo> crawl(final String url) {

        log.info("Fetching contents for url: {}", url);
        try {
            final Document doc = Jsoup.connect(url).timeout(crawlerProperties.getTimeOut())
                    .followRedirects(crawlerProperties.isFollowRedirects()).get();

            /** .select returns a list of links here **/
            final Elements links_ = doc.select("a[href]");
            final Elements links = new Elements(links_
                    .stream()
                    .filter(link -> !isRefCited(link))
                    .collect(Collectors.toList()));
            final String title = doc.title();
            log.debug("Fetched title: {}, links[{}] for url: {}", title, links.nextAll(), url);
            Element meta = (Element) nodeByAttr(doc.body().getElementById("app"),"class", "flex-row paper-meta");
            Element fieldOfStudy = meta!=null
                    && meta.childNodes().size()>=3 ? (Element) meta.childNode(2) : null;
            Element publishedDate = (Element) nodeByAttr(meta, "data-selenium-selector", "paper-year");
            String authorNames = nodesByAttr(doc.head(), "name", "citation_author")
                    .stream().map(author->author.attr("content"))
                    .reduce("", (l, r) -> new StringBuilder().append(l)
                            .append(l.isEmpty() || r.isEmpty() ?"":", ").append(r).toString());
            Optional<String> pdfURL =  nodesByAttr(doc.head(), "name", "citation_pdf_url")
                    .stream().map(url_ -> url_.attr("content")).findFirst();
            Optional<String> description = nodesByAttr(doc.head(), "name", "description")
                    .stream().map(d -> d.attr("content")).findFirst();

            return Optional.of(new PageInfo(title, url, links,
                    pdfURL.isPresent()? pdfURL.get() : null,
                    fieldOfStudy != null && fieldOfStudy.childNode(0) != null &&  fieldOfStudy.childNode(0) instanceof TextNode
                            ? ((TextNode) (fieldOfStudy.childNode(0))).text() : null,
                    publishedDate != null && publishedDate.childNode(0) != null
                            ? ((TextNode) publishedDate.childNode(0).childNode(0).childNode(0)).text() : null
                    , description.isPresent() ? description.get() : null
                    , authorNames));
        } catch (final IOException | IllegalArgumentException e) {
            log.error(String.format("Error getting contents of url %s", url), e);
            return Optional.empty();
        }
    }

    private boolean isRefCited(Element link) {
        return toNextRefNode(link.parentNode()) != null;
    }

    private Node toNextRefNode(Node node) {
        if (node == null)
            return null;
        return node.attributes().hasKey("data-selenium-selector")
                ? node.attributes().get("data-selenium-selector").contains("reference")
                ? node
                : toNextRefNode(node.parentNode())
                : toNextRefNode(node.parentNode());
    }

    private Node nodeByAttr(Node node, final String key, final String val) {
        if (node == null)
            return null;
        if (node.attributes() != null
                && node.attributes().hasKey(key)
                && node.attributes().get(key).equals(val))
            return node;
        Node out = null;
        for (Node child : node.childNodes()) {
            out = nodeByAttr(child, key, val);
            if (out != null)
                break;
        }
        return out;
//        Optional<Node> mayBe = node.childNodes().stream().filter(child -> nodeByAttr(child, key, val) != null).findFirst();
//        return mayBe.isPresent() ? mayBe.get() : null;
    }

    private List<Node> nodesByAttr(Node node, final String key, final String val) {
        return node.childNodes().stream().filter(child ->
        child !=null && child.attributes() != null
                && child.attributes().hasKey(key)
                && child.attributes().get(key).equals(val)).collect(Collectors.toList());
    }
}
