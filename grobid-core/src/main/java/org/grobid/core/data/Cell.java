package org.grobid.core.data;

import org.grobid.core.layout.LayoutToken;

import java.util.ArrayList;
import java.util.List;

public class Cell {

	private List<LayoutToken> tokenList;
	private double leftMargin;
	private double rightMargin;
	private double topMargin;
	private double bottomMargin;
	private double width;
	private double height;
	private boolean rowEnd = false;
	private boolean multiLine = false;
	private int lineCounter;

	void addToken (LayoutToken layoutToken) {

		if (this.tokenList == null) {
			this.tokenList = new ArrayList<>();
		}

		this.tokenList.add(layoutToken);

		if (layoutToken.getHeight() == 0.0) return;

		if (this.leftMargin == 0.0 || Double.compare(this.leftMargin, layoutToken.getX()) > 0) this.leftMargin = layoutToken.getX();
		if (this.rightMargin == 0.0 || Double.compare(this.rightMargin, layoutToken.getX() + layoutToken.getWidth()) < 0) this.rightMargin = layoutToken.getX() + layoutToken.getWidth();
	}

	public List<LayoutToken> getTokenList() {
		return this.tokenList;
	}

	public double getBottomMargin() {
		return bottomMargin;
	}

	public double getHeight() {
		return height;
	}

	public double getLeftMargin() {
		return leftMargin;
	}

	public double getRightMargin() {
		return rightMargin;
	}

	public double getTopMargin() {
		return topMargin;
	}

	public double getWidth() {
		return width;
	}

	public void setMultiLine(boolean multiLine) {
		this.multiLine = multiLine;
	}

	public boolean isMultiLine() {
		return multiLine;
	}

	public boolean isRowEnd() {
		return rowEnd;
	}

	public void setRowEnd(boolean rowEnd) {
		this.rowEnd = rowEnd;
	}

	public void setLineCounter(int lineCounter) {
		this.lineCounter = lineCounter;
	}

	public int getLineCounter() {
		return lineCounter;
	}

	public String getText() {
		StringBuilder stringBuilder = new StringBuilder();

		for(LayoutToken token: this.tokenList) {
			if (!token.getText().equals("\n")) {
				stringBuilder.append(token.getText());
			}
		}

		return stringBuilder.toString();
	}
}
