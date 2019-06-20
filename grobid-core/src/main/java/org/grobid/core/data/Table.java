package org.grobid.core.data;

import org.grobid.core.GrobidModels;
import org.apache.commons.lang3.StringUtils;
import org.grobid.core.document.xml.XmlBuilderUtils;
import org.grobid.core.document.Document;
import org.grobid.core.document.TEIFormatter;
import org.grobid.core.engines.Engine;
import org.grobid.core.engines.config.GrobidAnalysisConfig;
import org.grobid.core.layout.BoundingBox;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.utilities.BoundingBoxCalculator;
import org.grobid.core.utilities.LayoutTokensUtil;
import org.grobid.core.utilities.counters.CntManager;
import org.grobid.core.engines.counters.TableRejectionCounters;
import org.grobid.core.tokenization.TaggingTokenCluster;
import org.grobid.core.tokenization.TaggingTokenClusteror;
import org.grobid.core.utilities.KeyGen;
import org.grobid.core.engines.label.TaggingLabels;
import org.grobid.core.engines.label.TaggingLabel;

import java.awt.geom.Arc2D;
import java.util.*;

import nu.xom.Attribute;
import nu.xom.Element;
import nu.xom.Node;
import nu.xom.Text;

import static org.grobid.core.document.xml.XmlBuilderUtils.teiElement;
import static org.grobid.core.document.xml.XmlBuilderUtils.addXmlId;
import static org.grobid.core.document.xml.XmlBuilderUtils.textNode;

/**
 * Class for representing a table.
 *
 * @author Patrice Lopez
 */
public class Table extends Figure {
	private List<LayoutToken> contentTokens = new ArrayList<>();
	private List<LayoutToken> fullDescriptionTokens = new ArrayList<>();
	private boolean goodTable = true;

	public void setGoodTable(boolean goodTable) {
		this.goodTable = goodTable;
	}

    public Table() {
    	caption = new StringBuilder();
    	header = new StringBuilder();
    	content = new StringBuilder();
    	label = new StringBuilder();
    }

	@Override
    public String toTEI(GrobidAnalysisConfig config, Document doc, TEIFormatter formatter) {
		if (StringUtils.isEmpty(header) && StringUtils.isEmpty(caption)) {
			return null;
		}

		Element tableElement = XmlBuilderUtils.teiElement("figure");
		tableElement.addAttribute(new Attribute("type", "table"));
		if (id != null) {
			XmlBuilderUtils.addXmlId(tableElement, "tab_" + id);
		}

		tableElement.addAttribute(new Attribute("validated", String.valueOf(isGoodTable())));

		if ((config.getGenerateTeiCoordinates() != null) && (config.getGenerateTeiCoordinates().contains("figure"))) {
			XmlBuilderUtils.addCoords(tableElement, LayoutTokensUtil.getCoordsStringForOneBox(getLayoutTokens()));
		}

		Element headEl = XmlBuilderUtils.teiElement("head",
        		LayoutTokensUtil.normalizeText(header.toString()));

		Element labelEl = XmlBuilderUtils.teiElement("label",
        		LayoutTokensUtil.normalizeText(label.toString()));

		/*Element descEl = XmlBuilderUtils.teiElement("figDesc");
		descEl.appendChild(LayoutTokensUtil.normalizeText(caption.toString()).trim());
		if ((config.getGenerateTeiCoordinates() != null) && (config.getGenerateTeiCoordinates().contains("figure"))) {
			XmlBuilderUtils.addCoords(descEl, LayoutTokensUtil.getCoordsString(getFullDescriptionTokens()));
		}*/

        Element desc = null;
        if (caption != null) {
            // if the segment has been parsed with the full text model we further extract the clusters
            // to get the bibliographical references

            desc = XmlBuilderUtils.teiElement("figDesc");
            if (config.isGenerateTeiIds()) {
                String divID = KeyGen.getKey().substring(0, 7);
                addXmlId(desc, "_" + divID);
            }

            if ( (labeledCaption != null) && (labeledCaption.length() > 0) ) {
                TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.FULLTEXT, labeledCaption, captionLayoutTokens);
                List<TaggingTokenCluster> clusters = clusteror.cluster();                
                for (TaggingTokenCluster cluster : clusters) {
                    if (cluster == null) {
                        continue;
                    }

                    TaggingLabel clusterLabel = cluster.getTaggingLabel();
                    String clusterContent = LayoutTokensUtil.normalizeText(cluster.concatTokens());
                    if (clusterLabel.equals(TaggingLabels.CITATION_MARKER)) {
                        try {
                            List<Node> refNodes = formatter.markReferencesTEILuceneBased(
                                    cluster.concatTokens(),
                                    doc.getReferenceMarkerMatcher(),
                                    config.isGenerateTeiCoordinates("ref"), 
                                    false);
                            if (refNodes != null) {
                                for (Node n : refNodes) {
                                    desc.appendChild(n);
                                }
                            }
                        } catch(Exception e) {
                            LOGGER.warn("Problem when serializing TEI fragment for figure caption", e);
                        }
                    } else {
                        desc.appendChild(textNode(clusterContent));
                    }
                }
            } else {
                desc.appendChild(LayoutTokensUtil.normalizeText(caption.toString()).trim());
            }
        }


		Element contentEl = XmlBuilderUtils.teiElement("table");
		//contentEl.appendChild(LayoutTokensUtil.toText(getContentTokens()));
		processTableCells(contentEl);
		if ((config.getGenerateTeiCoordinates() != null) && (config.getGenerateTeiCoordinates().contains("figure"))) {
			XmlBuilderUtils.addCoords(contentEl, LayoutTokensUtil.getCoordsStringForOneBox(getContentTokens()));
		}

		tableElement.appendChild(headEl);
		tableElement.appendChild(labelEl);
        if (desc != null)
    		tableElement.appendChild(desc);
		tableElement.appendChild(contentEl);

		return tableElement.toXML();

//		if (config.isGenerateTeiCoordinates())
//			theTable.append(" coords=\"" + getCoordinates() + "\"");
//		theTable.append(">\n");
//		if (header != null) {
//	       	for(int i=0; i<indent+1; i++)
//				theTable.append("\t");
//			theTable.append("<head>").append(cleanString(
//				TextUtilities.HTMLEncode(header.toString())))
//				.append("</head>\n");
//		}
//		if (caption != null) {
//			for(int i=0; i<indent+1; i++)
//				theTable.append("\t");
//			theTable.append("<figDesc>").append(cleanString(
//				TextUtilities.HTMLEncode(TextUtilities.dehyphenize(caption.toString()))))
//				.append("</figDesc>\n");
//		}
//		if (uri != null) {
//	       	for(int i=0; i<indent+1; i++)
//				theTable.append("\t");
//			theTable.append("<graphic url=\"" + uri + "\" />\n");
//		}
//		if (content != null) {
//	       	for(int i=0; i<indent+1; i++)
//				theTable.append("\t");
//			theTable.append("<table>").append(cleanString(
//				TextUtilities.HTMLEncode(content.toString())))
//				.append("</table>\n");
//		}
//		for(int i=0; i<indent; i++)
//			theTable.append("\t");
//		theTable.append("</figure>\n");
//        return theTable.toString();
    }

    private String cleanString(String input) {
    	return input.replace("\n", " ").replace("  ", " ").trim();
    }

	private void processTableCells(Element elContent) {
		List<LayoutToken> tokens = contentTokens;
		List<Cell> cells = new ArrayList<>();
		LayoutToken lastToken = null;
		Cell currentCell = null;

		Map<Integer, Double> possibleRowDistances = getPossibleRowDistances(tokens);

		// Determine cells
		for (int y = 0; y < tokens.size(); y++) {
			LayoutToken token = tokens.get(y);
			if (isNewCell(lastToken, token, cells, possibleRowDistances, y, tokens)) {
				Cell cell = new Cell();

				if (currentCell != null && currentCell.isNextCellRightToLeft()) cell.setRightToLeft(true);

				currentCell = cell;
				cells.add(cell);
			}

			currentCell.addToken(token);
			lastToken = token;
		}


		// Write cells to the XML; check for rows

		Element currentRowNode = null;
		for (int i = 0; i < cells.size(); i++) {
			Cell cell = cells.get(i);

			if (i == 0) {
				Element rowNode = XmlBuilderUtils.teiElement("row");
				elContent.appendChild(rowNode);
				currentRowNode = rowNode;
			}

			Element cellNode = XmlBuilderUtils.teiElement("cell");
			cellNode.appendChild(cell.getText());

			currentRowNode.appendChild(cellNode);

			if (cell.isRowEnd()) {
				Element rowNode = XmlBuilderUtils.teiElement("row");
				elContent.appendChild(rowNode);
				currentRowNode = rowNode;
			}

		}
	}

	private Map<Integer, Double> getPossibleRowDistances(List<LayoutToken> tokens) {
		Map<Integer, Double> possibleRowDistances = new LinkedHashMap<>();
		LayoutToken lastRowTextToken = null;

		for (int i = 0; i < tokens.size(); i++) {

			LayoutToken rowToken = tokens.get(i);

			if (rowToken.getY() > 0 && lastRowTextToken == null) {
				lastRowTextToken = rowToken;
			}

			if (rowToken.getY() > 0 && lastRowTextToken != null) {
				double distance = Math.abs(rowToken.getY() - lastRowTextToken.getY());
				if (distance > lastRowTextToken.getHeight()) {
					possibleRowDistances.put(i, distance);
				}

				lastRowTextToken = rowToken;
			}
		}

		return possibleRowDistances;
	}

	private boolean isNewCell(LayoutToken lastToken, LayoutToken currentToken, List<Cell> cells, Map<Integer, Double> possibleRowDistances, int tokenNumber, List<LayoutToken> tokens) {

		if (cells.isEmpty()) {
			return true;
		}

		if (lastToken.getText().equals("\n") && currentToken.getX() != -1.0) {

			// New line is not always pointing at a start of a new cell; check the right margin of previous tokens (not ideal algorithm)
			Cell currentCell = cells.get(cells.size() - 1);

			if (!currentCell.isAcceptTokens()) {
				return true;
			}

			if (Double.compare(currentCell.getRightMargin(), currentToken.getX()) > 0 && cells.size() > 1) {

				// it's a current multi-line cell or the next row; check right margin of a previous cell
				Cell previousCell = cells.get(cells.size() - 2);
				if (currentCell.isRightToLeft()) {
				}
				if (Double.compare(previousCell.getRightMargin(), currentToken.getX()) > 0 && !currentCell.isRightToLeft()) {
					currentCell.setRowEnd(true);
					return true;
				} else {
					currentCell.setMultiLine(true);
					return false;
				}
				// If we don't have previous cell to compare with, rely on previous token
			} else if (Double.compare(currentCell.getRightMargin(), currentToken.getX()) > 0 && cells.size() == 1) {
				double firstY = currentCell.getTokenList().get(0).getY();
				double currentY = currentToken.getY();

				// If there are several cells in the row, the next token with the same vertical position indicates the new cell; may require approximation of height
				if (Double.compare(firstY, currentY) != 0) {

					// Try to extract the distance between new lines, if the next is 10% bigger than previous, assume it's a new Cell in the next Row
					double currentRowDistance = 0.0;
					double nextRowDistance = 0.0;
					if (possibleRowDistances.get(tokenNumber) != null) {
						currentRowDistance = possibleRowDistances.get(tokenNumber);
						int nextTokenKey = 0;
						for (Map.Entry<Integer, Double> key : possibleRowDistances.entrySet()) {
							if (key.getKey() > tokenNumber) {
								nextRowDistance = key.getValue();
								nextTokenKey = key.getKey();
								break;
							}
						}
					}

					if (currentRowDistance != 0.0 && nextRowDistance != 0.0 && Double.compare(currentRowDistance, nextRowDistance - nextRowDistance / 10) < 0) {
						currentCell.setAcceptTokens(false);
						return false;
					}

					// Deal with situation when PDFAlto parses cells from right to left; check all tokens in the next line
					if (Double.compare(currentCell.getLeftMargin(), currentToken.getX()) > 0) {
						return detectCellRecursively(currentCell, currentToken, tokens, tokenNumber);
					}

					currentCell.setMultiLine(true);
					return false;
				} else {
					return true;
				}

			} else {
				return true;
			}
		}

		return false;
	}

	private boolean detectCellRecursively(Cell currentCell, LayoutToken currentToken, List<LayoutToken> tokens, int tokenNumber) {

		LayoutToken nextToken = null;
		if (tokens.size() > tokenNumber + 1) {
			nextToken = tokens.get(tokenNumber + 1);
		}

		if (nextToken == null) return false;

		// Pass all tokens except we have the last in the line, don't compare
		if (!nextToken.getText().contains("\n") && nextToken.getY() > 0) {
			detectCellRecursively(currentCell, nextToken, tokens, tokenNumber + 1);
		} else if (!nextToken.getText().contains("\n") && nextToken.getY() < 0) {
			detectCellRecursively(currentCell, currentToken, tokens, tokenNumber + 1);
		}

		// Determine if next line represents next row
		if (Double.compare(currentCell.getLeftMargin(), currentToken.getX() + currentToken.getWidth()) > 0) {
			currentCell.setNextCellRightToLeft(true);
			return true;
		} else {
			return false;
		}
	}

	// if an extracted table passes some validations rules

	public boolean firstCheck() {
		goodTable = goodTable && validateTable();
		return goodTable;
	}

	public boolean secondCheck() {
		goodTable = goodTable && !badTableAdvancedCheck();
		return goodTable;
	}

	private boolean validateTable() {
		CntManager cnt = Engine.getCntManager();
		if (StringUtils.isEmpty(label) || StringUtils.isEmpty(header) || StringUtils.isEmpty(content)) {
			cnt.i(TableRejectionCounters.EMPTY_LABEL_OR_HEADER_OR_CONTENT);
			return false;
		}

		try {
			Integer.valueOf(getLabel().trim(), 10);
		} catch (NumberFormatException e) {
			cnt.i(TableRejectionCounters.CANNOT_PARSE_LABEL_TO_INT);
			return false;
		}
		if (!getHeader().toLowerCase().startsWith("table")) {
			cnt.i(TableRejectionCounters.HEADER_NOT_STARTS_WITH_TABLE_WORD);
			return false;
		}
		return true;
	}

	private boolean badTableAdvancedCheck() {
		CntManager cnt = Engine.getCntManager();
		BoundingBox contentBox = BoundingBoxCalculator.calculateOneBox(contentTokens, true);
		BoundingBox descBox = BoundingBoxCalculator.calculateOneBox(fullDescriptionTokens, true);

		if (contentBox.getPage() != descBox.getPage()) {
            cnt.i(TableRejectionCounters.HEADER_AND_CONTENT_DIFFERENT_PAGES);
			return true;
        }

		if (contentBox.intersect(descBox)) {
            cnt.i(TableRejectionCounters.HEADER_AND_CONTENT_INTERSECT);
			return true;
        }

		if (descBox.area() > contentBox.area()) {
            cnt.i(TableRejectionCounters.HEADER_AREA_BIGGER_THAN_CONTENT);
			return true;
        }

		if (contentBox.getHeight() < 40) {
            cnt.i(TableRejectionCounters.CONTENT_SIZE_TOO_SMALL);
			return true;
        }

		if (contentBox.getWidth() < 100) {
            cnt.i(TableRejectionCounters.CONTENT_WIDTH_TOO_SMALL);
			return true;
        }

		if (contentTokens.size() < 10) {
            cnt.i(TableRejectionCounters.FEW_TOKENS_IN_CONTENT);
			return true;
        }

		if (fullDescriptionTokens.size() < 5) {
            cnt.i(TableRejectionCounters.FEW_TOKENS_IN_HEADER);
			return true;
        }
		return false;
	}

	public List<LayoutToken> getContentTokens() {
		return contentTokens;
	}

	public List<LayoutToken> getFullDescriptionTokens() {
		return fullDescriptionTokens;
	}

	public boolean isGoodTable() {
		return goodTable;
	}
}